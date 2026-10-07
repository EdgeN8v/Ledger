import os
from datetime import datetime, timezone, timedelta

os.environ["DATABASE_URL"] = "sqlite:///./test_ledger.db"
os.environ["REGISTRATION_OPEN"] = "true"
os.environ["SCHEDULER_ENABLED"] = "false"

import pytest
from fastapi.testclient import TestClient

from app.database import Base, engine
from app.main import app
from app.parsers import parse_message, looks_financial

CN = timezone(timedelta(hours=8))
CCB = "[39条]【建设银行】您账户1234于10月6日21:25向支付宝-淘宝-某店铺充值支出2元,可用余额800.00元。"


def ms(y, mo, d, h, mi):
    return int(datetime(y, mo, d, h, mi, tzinfo=CN).timestamp() * 1000)


@pytest.fixture()
def client():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    c = TestClient(app)
    tok = c.post("/auth/register", json={"username": "u", "password": "p", "display_name": "U"}).json()["access_token"]
    c.headers["Authorization"] = f"Bearer {tok}"
    return c


def test_ccb_sms_amount_is_the_payment_not_the_balance():
    p = parse_message("sms", "建设银行", CCB, datetime(2026, 10, 6, 21, 25))
    assert str(p.amount) == "2"
    assert p.tx_type == "expense"
    assert p.occurred_at == datetime(2026, 10, 6, 21, 25)
    assert p.account_hint == "建设银行1234"
    assert p.merchant == "支付宝-淘宝-某店铺"


def test_income_sms():
    t = "【建设银行】您账户1234于10月7日09:10收入人民币5,000.00元,可用余额8811.96元。"
    p = parse_message("sms", "建设银行", t, datetime(2026, 10, 7, 9, 10))
    assert p.tx_type == "income" and str(p.amount) == "5000.00"


def test_otp_and_chatter_are_ignored():
    assert parse_message("sms", "x", "【建设银行】验证码123456,支出2元请勿泄露", datetime.now()) is None
    assert not looks_financial("今晚吃什么")


def test_ingest_creates_then_dedupes_resend(client):
    body = {"source": "com.android.mms", "title": "建设银行", "text": CCB, "posted_at_ms": ms(2026, 10, 6, 21, 25)}
    r1 = client.post("/ingest", json=body).json()
    assert r1["status"] == "created" and r1["transaction"]["amount"] in ("2", "2.00", 2)
    r2 = client.post("/ingest", json=body).json()
    assert r2["status"] == "duplicate"
    assert len(client.get("/transactions").json()) == 1


def test_same_text_reposted_a_minute_later_is_not_a_second_record(client):
    base = {"source": "com.android.mms", "title": "建设银行", "text": CCB}
    client.post("/ingest", json={**base, "posted_at_ms": ms(2026, 10, 6, 21, 25)})
    r = client.post("/ingest", json={**base, "posted_at_ms": ms(2026, 10, 6, 21, 26)}).json()
    assert r["status"] == "duplicate"
    assert len(client.get("/transactions").json()) == 1


def test_wallet_notification_and_bank_sms_merge_into_one(client):
    wx = {"source": "com.tencent.mm", "title": "微信支付", "text": "微信支付: 已支付¥2.00 向某店铺付款",
          "posted_at_ms": ms(2026, 10, 6, 21, 25)}
    r1 = client.post("/ingest", json=wx).json()
    assert r1["status"] == "created"
    sms = {"source": "com.android.mms", "title": "建设银行", "text": CCB, "posted_at_ms": ms(2026, 10, 6, 21, 26)}
    r2 = client.post("/ingest", json=sms).json()
    assert r2["status"] == "merged"
    txs = client.get("/transactions").json()
    assert len(txs) == 1
    assert "wechat" in txs[0]["sources"] and "sms:建设银行" in txs[0]["sources"]


def test_two_purchases_from_one_source_stay_two_records(client):
    a = "【建设银行】您账户1234于10月6日12:00在星巴克消费支出30元,可用余额100元。"
    b = "【建设银行】您账户1234于10月6日12:03在瑞幸消费支出30元,可用余额70元。"
    client.post("/ingest", json={"source": "sms", "title": "建设银行", "text": a, "posted_at_ms": ms(2026, 10, 6, 12, 0)})
    r = client.post("/ingest", json={"source": "sms", "title": "建设银行", "text": b, "posted_at_ms": ms(2026, 10, 6, 12, 3)}).json()
    assert r["status"] == "created"
    assert len(client.get("/transactions").json()) == 2


def test_unparsed_payment_like_text_lands_in_failed_list(client):
    r = client.post("/ingest", json={"source": "com.tencent.mm", "title": "微信支付",
                                     "text": "你有一笔新的付款 88.8元 请查看", "posted_at_ms": ms(2026, 10, 6, 10, 0)}).json()
    # either parsed by the generic wallet template or stored for review; it must never be lost
    assert r["status"] in ("created", "failed")


def test_learned_merchant_category(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    sms = lambda m, t: {"source": "sms", "title": "建设银行", "posted_at_ms": ms(2026, 10, 6, 12, m),
                        "text": f"【建设银行】您账户1234于10月6日12:{m:02d}向{t}支出18元,可用余额100元。"}
    tx = client.post("/ingest", json=sms(0, "神秘小店")).json()["transaction"]
    assert tx["needs_review"] is True
    client.put(f"/transactions/{tx['id']}", json={"category_id": cats["餐饮"]})
    tx2 = client.post("/ingest", json=sms(40, "神秘小店")).json()["transaction"]
    assert tx2["category_id"] == cats["餐饮"] and tx2["needs_review"] is False


def test_invite_code_is_not_guessable(client):
    me = client.get("/auth/me").json()
    assert me["invite_code"] and not me["invite_code"].isdigit()
    r = client.post("/auth/join", json={"username": "w", "password": "p", "display_name": "W", "invite_code": "1"})
    assert r.status_code == 404
    r = client.post("/auth/join", json={"username": "w", "password": "p", "display_name": "W", "invite_code": me["invite_code"]})
    assert r.status_code == 200


def test_sms_group_counter_change_is_not_a_new_record(client):
    base = {"source": "com.android.mms", "title": "建设银行"}
    a = CCB
    b = CCB.replace("[39条]", "[40条]")
    client.post("/ingest", json={**base, "text": a, "posted_at_ms": ms(2026, 10, 6, 21, 25)})
    r = client.post("/ingest", json={**base, "text": b, "posted_at_ms": ms(2026, 10, 6, 21, 27)}).json()
    assert r["status"] == "duplicate"
    assert len(client.get("/transactions").json()) == 1


def _sms(client, text, h, m, day=6):
    return client.post("/ingest", json={"source": "com.android.mms", "title": "银行", "text": text,
                                        "posted_at_ms": ms(2026, 10, day, h, m)}).json()


def test_balance_is_tracked_per_card_and_totalled(client):
    _sms(client, "【建设银行】您账户1234于10月6日21:25向淘宝支出2元,可用余额800.00元。", 21, 25)
    _sms(client, "【建设银行】您账户1234于10月6日23:07向京东支出19.9元,可用余额780.06元。", 23, 7)
    _sms(client, "【交通银行】您尾号5678的储蓄卡于10月5日09:30消费人民币10.00元,余额500.00元。", 9, 30, day=5)
    ov = client.get("/accounts").json()
    by = {a["name"]: a for a in ov["accounts"]}
    assert float(by["建设银行1234"]["balance"]) == 780.06
    assert float(by["交通银行5678"]["balance"]) == 500.0
    assert float(ov["total"]) == 1280.06


def test_older_sms_never_overwrites_a_newer_balance(client):
    _sms(client, "【建设银行】您账户1234于10月6日23:07向京东支出19.9元,可用余额780.06元。", 23, 7)
    _sms(client, "【建设银行】您账户1234于10月6日21:25向淘宝支出2元,可用余额800.00元。", 21, 25)
    by = {a["name"]: a for a in client.get("/accounts").json()["accounts"]}
    assert float(by["建设银行1234"]["balance"]) == 780.06


def test_manual_account_counts_in_total(client):
    client.post("/accounts", json={"name": "微信零钱", "kind": "wallet", "balance": "120.50"})
    assert float(client.get("/accounts").json()["total"]) == 120.5


def test_refund_cancels_the_expense_in_summary(client):
    _sms(client, "【建设银行】您账户1234于10月6日23:07向京东支付-京东商城支出19.9元,可用余额780.06元。", 23, 7)
    r = _sms(client, "【建设银行】您账户1234于10月8日10:00收到京东支付-京东商城退款收入19.9元,可用余额3812.00元。", 10, 0, day=8)
    assert r["transaction"]["refund_of_id"] is not None
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_expense"]) == 0.0
    assert float(s["total_income"]) == 0.0
    assert s["by_category"] == []
    by = {a["name"]: a for a in client.get("/accounts").json()["accounts"]}
    assert float(by["建设银行1234"]["balance"]) == 3812.0


def test_partial_refund_leaves_the_rest_as_expense(client):
    _sms(client, "【建设银行】您账户1234于10月6日12:00向京东商城支出100元,可用余额900元。", 12, 0)
    _sms(client, "【建设银行】您账户1234于10月7日12:00京东商城退款收入40元,可用余额940元。", 12, 0, day=7)
    # the 40 refund cannot be pinned to the 100 purchase by amount, but money coming back is never income:
    # it reduces spending (100 - 40 = 60) and is left flagged for review
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_expense"]) == 60.0 and float(s["total_income"]) == 0.0


def test_specific_keyword_beats_generic(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    r = _sms(client, "【建设银行】您账户1234于10月6日12:00向京东健康-大药房支出30元,可用余额900元。", 12, 0)
    assert r["transaction"]["category_id"] == cats["医疗"]
    r = _sms(client, "【建设银行】您账户1234于10月6日13:00向美团外卖-神秘小店支出25元,可用余额875元。", 13, 0)
    assert r["transaction"]["category_id"] == cats["餐饮"]


def test_real_alipay_notifications(client):
    pay = client.post("/ingest", json={"source": "com.eg.android.AlipayGphone", "title": "交易提醒",
                                       "text": "你有一笔39.90元的支出，领立减1.08元权益。",
                                       "posted_at_ms": ms(2026, 10, 6, 23, 20)}).json()
    assert pay["status"] == "created" and float(pay["transaction"]["amount"]) == 39.9
    assert pay["transaction"]["tx_type"] == "expense"
    # the bank SMS for the same payment merges into it instead of becoming a second record
    sms = _sms(client, "【建设银行】您账户1234于10月6日23:20向支付宝-淘宝-鸡蛋店支出39.9元,可用余额3752.16元。", 23, 21)
    assert sms["status"] == "merged"
    ref = client.post("/ingest", json={"source": "com.eg.android.AlipayGphone", "title": "退款提醒",
                                       "text": "你收到一笔39.90元退款，点此查看账单详情！",
                                       "posted_at_ms": ms(2026, 10, 6, 23, 40)}).json()
    assert ref["transaction"]["refund_of_id"] == pay["transaction"]["id"]
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_expense"]) == 0.0


def test_transfer_between_own_cards_is_not_spending(client):
    _sms(client, "【交通银行】您尾号5678的储蓄卡于10月6日23:30转出人民币0.01元。", 23, 30)
    r = _sms(client, "【建设银行】您账户1234于10月6日23:31收入0.01元,可用余额3750.00元。", 23, 31)
    assert r["transaction"]["is_transfer"] is True
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_expense"]) == 0.0 and float(s["total_income"]) == 0.0


def test_refund_arriving_before_its_payment_still_pairs(client):
    ref = client.post("/ingest", json={"source": "com.eg.android.AlipayGphone", "title": "退款提醒",
                                       "text": "你收到一笔39.90元退款，点此查看账单详情！",
                                       "posted_at_ms": ms(2026, 10, 6, 23, 40)}).json()
    assert ref["transaction"]["refund_of_id"] is None
    client.post("/ingest", json={"source": "com.eg.android.AlipayGphone", "title": "交易提醒",
                                 "text": "你有一笔39.90元的支出，领立减1.08元权益。",
                                 "posted_at_ms": ms(2026, 10, 6, 23, 20)})
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_expense"]) == 0.0 and float(s["total_income"]) == 0.0


REAL = {
    "wx":  "【建设银行】您账户1234于9月27日13:54向微信支付-某烧烤店支出145元,可用余额1000.00元。",
    "ali": "【建设银行】您账户1234于9月28日08:21向支付宝-饿了么/上海拉扎斯信息科技有限公司支出16元,可用余额4462.73元。",
    "ext": "【建设银行】您账户1234于9月29日12:15向支付宝外部商户-某小吃店支出10.3元,可用余额3869.73元。",
    "card": "【建设银行】您账户1234于9月28日09:58向易宝支付-浦发银行信用卡还款支出487元,可用余额3975.73元。",
    "refund": "【建设银行】京东支付-京东商城业务于9月30日15:26向您账户1234消费退货存入2979.88元,可用余额6555.14元。",
    "selfin": "【建设银行】张三于10月6日23:35向您账户1234存入0.01元,可用余额800.02元。附言：转账。",
    "bocom": "【交通银行】贵账户*5678于2026年10月06日23:35在上海市分行跨行转账转出资金0.01元，现余额12345.67元，对方户名：张三，附言：转账。",
    "promo": "【哈啰顺风车】国庆订单爆发，您的高档车单均收入更高！新车主权益升级，最高得178元👉 a.c3x.me/eHmoqm 拒收请回复R",
}


def _p(k, y=2026, mo=10, d=6):
    return parse_message("sms", "x", REAL[k], datetime(y, mo, d, 12, 0))


def test_real_ccb_variants():
    p = _p("wx", mo=9, d=27);  assert (str(p.amount), p.merchant, p.tx_type) == ("145", "微信支付-某烧烤店", "expense")
    p = _p("ali", mo=9, d=28); assert p.merchant.startswith("支付宝-饿了么") and str(p.amount) == "16"
    p = _p("ext", mo=9, d=29); assert p.merchant.startswith("支付宝外部商户-某小吃") and str(p.amount) == "10.3"
    p = _p("card", mo=9, d=28); assert p.is_transfer and str(p.amount) == "487"
    p = _p("refund", mo=9, d=30)
    assert p.tx_type == "income" and p.is_refund and str(p.amount) == "2979.88" and p.merchant == "京东支付-京东商城业务"
    assert str(p.balance) == "6555.14" and p.account_hint == "建设银行1234"
    p = _p("selfin")
    assert p.tx_type == "income" and p.merchant == "张三" and str(p.amount) == "0.01" and not p.is_refund


def test_real_bocom_sms():
    p = _p("bocom")
    assert p.tx_type == "expense" and str(p.amount) == "0.01"
    assert p.account_hint == "交通银行5678" and str(p.balance) == "12345.67"
    assert p.merchant == "张三"


def test_promo_sms_is_never_a_transaction():
    assert _p("promo") is None


def test_real_self_transfer_and_card_repayment_do_not_count(client):
    _sms(client, REAL["bocom"], 23, 35)
    r = _sms(client, REAL["selfin"], 23, 35)
    assert r["transaction"]["is_transfer"] is True
    r = _sms(client, REAL["card"], 9, 58, day=6)
    assert r["transaction"]["is_transfer"] is True
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_expense"]) == 0.0 and float(s["total_income"]) == 0.0


def test_payday_cycle_summary_and_salary_category(client):
    r = client.put("/auth/ledger-settings", json={"cycle_start_day": 20}).json()
    assert r["cycle_start_day"] == 20
    sal = _sms(client, "【建设银行】您账户1234于9月20日09:19存入10674.84元,可用余额16686.56元。附言：工资。", 9, 19, day=19)
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    assert cats[sal["transaction"]["category_id"]] == "工资"
    _sms(client, "【建设银行】您账户1234于10月6日12:00向美团外卖-老王面馆支出25元,可用余额900元。", 12, 0)
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()
    assert float(s["total_income"]) == 10674.84 and float(s["total_expense"]) == 25.0
    assert s["period_start"] == "2026-09-20" and s["period_end"] == "2026-10-19"
    s2 = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s2["total_income"]) == 0.0 and float(s2["total_expense"]) == 0.0


def test_long_digit_runs_are_masked_before_storage(client):
    t = "【建设银行】您账户1234于7月1日12:19存入1800元,可用余额7145.35元。附言：电子退库(纳税人编码=123455678012345678)。"
    r = _sms(client, t, 12, 19, day=1)
    assert "123455678012345678" not in (r["transaction"]["raw_text"] or "")
    ev = client.get("/ingest/failed").json()
    assert all("123455678012345678" not in (e["text"] or "") for e in ev)


def test_money_from_the_holders_own_name_is_a_transfer(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    r = _sms(client, "【建设银行】张三于6月26日20:20向您账户1234存入15000元,可用余额18360.51元。附言：电子汇入。", 20, 20, day=26)
    assert r["transaction"]["is_transfer"] is True and r["transaction"]["needs_review"] is False


def test_filters_paging_and_search(client):
    for i in range(12):
        _sms(client, f"【建设银行】您账户1234于10月6日{10 + i // 6}:{(i % 6) * 9 + 1:02d}向美团外卖-老王面馆{i}支出{10 + i}元,可用余额900元。", 10 + i // 6, (i % 6) * 9 + 1)
    _sms(client, "【建设银行】您账户1234于10月5日09:00向京东商城支出99元,可用余额800元。", 9, 0, day=5)
    allr = client.get("/transactions", params={"limit": 50}).json()
    assert len(allr) == 13
    page1 = client.get("/transactions", params={"limit": 5, "offset": 0}).json()
    page2 = client.get("/transactions", params={"limit": 5, "offset": 5}).json()
    assert len(page1) == 5 and len(page2) == 5 and not ({t["id"] for t in page1} & {t["id"] for t in page2})
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    food = client.get("/transactions", params={"category_id": cats["餐饮"]}).json()
    assert len(food) == 12
    assert len(client.get("/transactions", params={"q": "京东"}).json()) == 1
    assert len(client.get("/transactions", params={"date_from": "2026-10-06", "date_to": "2026-10-06"}).json()) == 12
    assert len(client.get("/transactions", params={"tx_type": "income"}).json()) == 0


def test_unknown_merchants_get_a_category_not_none(client):
    r = _sms(client, "【建设银行】您账户1234于10月6日12:00向支付宝外部商户-金源泉信息技术支出11元,可用余额900元。", 12, 0)
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    assert cats[r["transaction"]["category_id"]] == "其他支出" and r["transaction"]["needs_review"] is True
    r = _sms(client, "【建设银行】您账户1234于10月6日13:00向支付宝外部商户-孙鸿艳支出20元,可用余额880元。", 13, 0)
    assert cats[r["transaction"]["category_id"]] == "餐饮" and r["transaction"]["needs_review"] is True


def test_long_press_category_change_fixes_the_whole_merchant(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    ids = []
    for h in (10, 11, 12):
        r = _sms(client, f"【建设银行】您账户1234于10月6日{h}:00向支付宝外部商户-金源泉信息技术支出11元,可用余额900元。", h, 0)
        ids.append(r["transaction"]["id"])
    client.put(f"/transactions/{ids[0]}", json={"category_id": cats["车辆出行"]})
    for i in ids:
        t = [x for x in client.get("/transactions").json() if x["id"] == i][0]
        assert t["category_id"] == cats["车辆出行"] and t["needs_review"] is False


def test_refund_inherits_the_category_of_its_purchase(client):
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    p = _sms(client, "【建设银行】您账户1234于10月6日23:07向京东支付-京东商城支出19.9元,可用余额780.06元。", 23, 7)
    r = _sms(client, "【建设银行】京东支付-某自营官方店于10月6日23:26向您账户1234消费退货存入19.9元,可用余额800.00元。", 23, 26)
    assert r["transaction"]["refund_of_id"] == p["transaction"]["id"]
    assert r["transaction"]["category_id"] == p["transaction"]["category_id"] and cats[r["transaction"]["category_id"]] == "购物"


def test_split_refunds_cancel_one_purchase_and_show_refunded_amount(client):
    pay = _sms(client, "【建设银行】您账户1234于8月13日10:00向京东支付-京东商城-某品牌京东自营旗舰店支出3836.39元,可用余额5000元。", 10, 0, day=13)
    r1 = _sms(client, "【建设银行】京东支付-某品牌京东*于8月15日12:00向您账户1234消费退货存入2668.19元,可用余额7668.19元。", 12, 0, day=15)
    r2 = _sms(client, "【建设银行】京东支付-某品牌京东*于8月24日20:31向您账户1234消费退货存入1168.2元,可用余额8836.39元。", 20, 31, day=24)
    assert r1["transaction"]["refund_of_id"] == pay["transaction"]["id"] == r2["transaction"]["refund_of_id"]
    listed = {t["id"]: t for t in client.get("/transactions").json()}
    assert float(listed[pay["transaction"]["id"]]["refunded_amount"]) == 3836.39
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 8}).json()
    assert float(s["total_expense"]) == 0.0


def test_refund_for_a_different_shop_with_other_amount_is_not_attached(client):
    _sms(client, "【建设银行】您账户1234于8月13日10:00向美团外卖-老王面馆支出50元,可用余额900元。", 10, 0, day=13)
    r = _sms(client, "【建设银行】京东支付-韶音京东*于8月15日12:00向您账户1234消费退货存入20元,可用余额920元。", 12, 0, day=15)
    assert r["transaction"]["refund_of_id"] is None and r["transaction"]["needs_review"] is True


def test_refund_reduces_the_period_of_its_purchase_not_its_own(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    _sms(client, "【建设银行】您账户1234于9月23日13:00向京东支付-京东商城-某汽车用品旗舰店支出2979.88元,可用余额5000元。", 13, 0, day=23)
    # refunded after the next payday: September must still end up without that purchase
    r = client.post("/ingest", json={"source": "com.android.mms", "title": "x", "posted_at_ms": ms(2026, 10, 21, 9, 0),
                                     "text": "【建设银行】京东支付-某汽车用品旗舰店于10月21日09:00向您账户1234消费退货存入2979.88元,可用余额8000元。"}).json()
    assert r["transaction"]["refund_of_id"] is not None
    sep = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()
    octo = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(sep["total_expense"]) == 0.0 and float(octo["total_expense"]) == 0.0 and float(octo["total_income"]) == 0.0


def test_plan_status_and_analysis(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    _sms(client, "【建设银行】您账户1234于9月20日09:19存入10000元,可用余额12000元。附言：工资。", 9, 19, day=19)
    for d, amt in ((21, 100), (22, 300), (23, 50)):
        _sms(client, f"【建设银行】您账户1234于9月{d}日12:00向美团外卖-老王面馆支出{amt}元,可用余额9000元。", 12, 0, day=d)
    client.put("/plan", json={"savings_target": 3000, "fixed_expenses": 2000})
    st = client.get("/plan/status", params={"year": 2026, "month": 9}).json()
    assert float(st["income_basis"]) >= 10000 and float(st["budget"]) == 5000.0     # 10000 - 2000 - 3000
    assert float(st["spent"]) == 450.0 and float(st["remaining"]) == 4550.0 and st["state"] in ("on_track", "at_risk")
    a = client.get("/analysis/cycle", params={"year": 2026, "month": 9}).json()
    assert float(a["total_expense"]) == 450.0 and a["categories"][0]["name"] == "餐饮" and a["top_shops"][0]["count"] == 3
    y = client.get("/analysis/year", params={"year": 2026}).json()
    assert float(y["income"]) == 10000.0 and float(y["expense"]) == 450.0 and y["months"]


def test_sorting_for_the_category_drilldown(client):
    for amt, h in ((30, 9), (300, 10), (5, 11)):
        _sms(client, f"【建设银行】您账户1234于10月6日{h}:00向美团外卖-老王面馆支出{amt}元,可用余额900元。", h, 0)
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    q = {"category_id": cats["餐饮"]}
    by = lambda sort: [float(t["amount"]) for t in client.get("/transactions", params={**q, "sort": sort}).json()]
    assert by("amount_desc") == [300.0, 30.0, 5.0] and by("amount_asc") == [5.0, 30.0, 300.0]
    assert by("time_desc") == [5.0, 300.0, 30.0] and by("time_asc") == [30.0, 300.0, 5.0]


def test_daily_report_is_written_by_the_server_and_read_by_the_app(client):
    from app import reports
    from app.database import SessionLocal
    from app.models import Ledger
    _sms(client, "【建设银行】您账户1234于10月6日12:00向美团外卖-老王面馆支出25元,可用余额900元。", 12, 0)
    with SessionLocal() as db:
        row = reports.generate_for(db, db.query(Ledger).first())
        assert row.headline and "本账期已花" in row.headline
    r = client.get("/reports/latest").json()
    assert r["headline"] and isinstance(r["insights"], list) and "plan_state" in r
    assert client.get("/reports/history").json()
    # the scheduled pass makes one only when none exists for today (idempotent)
    made_first = reports.run_due_reports()
    made_again = reports.run_due_reports()
    assert made_again == 0


def test_fixed_items_file_payments_and_reserve_budget(client):
    import json as _json
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    items = [
        {"name": "房租", "monthly": 4900, "tracked": False, "category": "居住", "match_merchants": ["李四"]},
        {"name": "妈妈生活费", "monthly": 1000, "tracked": True, "category": "家人", "match_amount": [1000],
         "match_merchants": ["微信"], "due_day": 20, "tolerance_days": 4},
    ]
    client.put("/plan", json={"savings_target": 2000, "expected_income": 10000, "fixed_items": items})
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    mom = _sms(client, "【建设银行】您账户1234于9月20日19:19向微信转账支出1000元,可用余额900元。", 19, 19, day=20)
    assert cats[mom["transaction"]["category_id"]] == "家人"
    rent = _sms(client, "【建设银行】您账户1234于9月26日10:00向李四跨行转出支出14700元,可用余额900元。", 10, 0, day=26)
    assert cats[rent["transaction"]["category_id"]] == "居住"
    _sms(client, "【建设银行】您账户1234于9月22日12:00向美团外卖-老王面馆支出200元,可用余额700元。", 12, 0, day=22)
    st = client.get("/plan/status", params={"year": 2026, "month": 9}).json()
    # reserve: rent 4900 + mum 1000; free budget = 10000 - 5900 - 2000 = 2100; free spending = only the 200 meal
    assert float(st["fixed_total"]) == 5900.0 and float(st["budget"]) == 2100.0
    assert float(st["spent"]) == 200.0
    by = {i["name"]: i for i in st["fixed_items"]}
    assert by["妈妈生活费"]["state"] == "paid" and by["房租"]["state"] == "paid"


def test_car_and_transport_are_one_category(client):
    names = [c["name"] for c in client.get("/categories").json()]
    assert "车辆出行" in names and "交通" not in names and "车辆" not in names and "家人" in names


def test_trip_is_counted_but_not_extrapolated(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    client.put("/plan", json={"savings_target": 1000, "expected_income": 10000})
    for h, t in ((9, "中国海油协和石油支出500元"), (12, "微信支付-ETC支出144.88元"), (18, "微信支付-肯德基支出40元")):
        _sms(client, f"【建设银行】您账户1234于9月27日{h}:00向{t},可用余额900元。".replace("支出500元", "支出500元"), h, 0, day=27)
    base = client.get("/plan/status", params={"year": 2026, "month": 9}).json()
    trip = client.post("/trips", json={"name": "中秋去黄山", "start_date": "2026-09-27", "end_date": "2026-09-27"}).json()
    assert trip["count"] == 3 and float(trip["total"]) == 684.88
    after = client.get("/plan/status", params={"year": 2026, "month": 9}).json()
    assert float(after["trip_total"]) == 684.88 and float(after["spent"]) == 0.0
    # money is spent either way
    assert float(after["remaining"]) == float(base["remaining"])
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()
    assert float(s["total_expense"]) == 684.88
    a = client.get("/analysis/cycle", params={"year": 2026, "month": 9}).json()
    assert a["trips"][0]["name"] == "中秋去黄山"
    client.delete(f"/trips/{trip['id']}")
    assert float(client.get("/plan/status", params={"year": 2026, "month": 9}).json()["trip_total"]) == 0.0


def test_variable_salary_uses_the_cautious_estimate_then_the_real_pay(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    for m, amt in ((7, 12000), (8, 10000), (9, 11000)):
        _sms(client, f"【建设银行】您账户1234于{m}月20日09:19存入{amt}元,可用余额20000元。附言：工资。", 9, 19, day=19) if False else \
            client.post("/ingest", json={"source": "sms", "title": "x", "posted_at_ms": ms(2026, m, 20, 9, 19),
                "text": f"【建设银行】您账户1234于{m}月20日09:19存入{amt}元,可用余额20000元。附言：工资。"})
    p = client.get("/plan").json()
    assert float(p["estimated_income"]) == 10000.0          # the lowest of the last three, not the average
    client.put("/plan", json={"savings_target": 1000})
    before = client.get("/plan/status", params={"year": 2026, "month": 10}).json()   # payday not reached yet
    assert float(before["income_basis"]) == 10000.0
    st = client.get("/plan/status", params={"year": 2026, "month": 9}).json()
    assert float(st["income_basis"]) == 11000.0              # real pay arrived: use it


def test_dismissed_trip_suggestion_is_not_offered_again(client):
    for h in range(3):
        client.post("/ingest", json={"source": "sms", "title": "x", "posted_at_ms": ms(2026, 10, 3, 9 + h, 0),
            "text": f"【建设银行】您账户1234于10月3日{9 + h}:00向微信支付-ETC支出{20 + h}元,可用余额900元。"})
    s = client.get("/trips/suggestions").json()
    assert s and s[0]["start_date"] <= "2026-10-03"
    client.post("/trips/suggestions/dismiss", json={"start_date": s[0]["start_date"], "end_date": s[0]["end_date"]})
    assert client.get("/trips/suggestions").json() == []


def test_a_learned_shop_is_recognised_through_any_payment_channel(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    first = _sms(client, "【建设银行】您账户1234于9月27日12:00向支付宝外部商户-某贸易有限公司支出9元,可用余额900元。", 12, 0, day=27)["transaction"]
    client.put(f"/transactions/{first['id']}", json={"category_id": cats["车辆出行"]})
    later = _sms(client, "【建设银行】您账户1234于10月5日15:20向微信支付-某贸易有限公司支出6元,可用余额890元。", 15, 20, day=5)["transaction"]
    assert later["category_id"] == cats["车辆出行"] and later["needs_review"] is False
    again = _sms(client, "【建设银行】您账户1234于10月6日15:20向支付宝-某贸易有限公司支出5元,可用余额880元。", 15, 20, day=6)["transaction"]
    assert again["category_id"] == cats["车辆出行"]


def test_travel_agency_and_airline_purchases_are_travel_not_entertainment(client):
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    for shop in ("支付宝外部商户-携程旅行网", "支付宝-中国东方航空股份有限公司", "支付宝-中国南方航空", "支付宝-春秋航空"):
        r = _sms(client, f"【某银行】您账户1234于10月7日11:26向{shop}支出748.95元,可用余额900元。", 11, 26, day=7)
        assert cats[r["transaction"]["category_id"]] == "车辆出行", shop


def test_business_trip_is_receivable_not_spending_until_paid_back(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    pay = lambda d, h, shop, amt: _sms(client, f"【某银行】您账户1234于10月{d}日{h}:00向{shop}支出{amt}元,可用余额9000元。", h, 0, day=d)
    pay(7, 11, "支付宝外部商户-携程旅行网", 1200)        # flights, booked before departure
    pay(9, 7, "微信支付-滴滴出行", 45)                    # taxi to the airport
    pay(10, 12, "微信支付-某酒店", 680)                   # hotel
    pay(12, 21, "微信支付-出租车", 60)                    # taxi home
    pay(15, 12, "微信支付-某面馆", 30)                    # ordinary life after the trip
    trip = client.post("/trips", json={"name": "北京出差", "start_date": "2026-10-07", "end_date": "2026-10-12", "kind": "business",
                                       "daily_allowance": 180, "commute_allowance": 80}).json()
    assert trip["count"] == 4 and float(trip["total"]) == 1985.0
    assert trip["days"] == 6 and float(trip["allowance_total"]) == 80 + 180 * 6
    assert float(trip["expected_reimbursement"]) == 1985.0 + 1160.0
    # not the user's spending: only the ordinary meal counts
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()
    assert float(s["total_expense"]) == 30.0
    # ...but remembered as money owed to him
    acc = client.get("/accounts").json()
    assert float(acc["receivable"]) == 3145.0 and "北京出差" in acc["receivable_trips"][0]
    # the employer pays 3145 back: the advanced part is not income, the allowances are
    inc = client.post("/ingest", json={"source": "sms", "title": "x", "posted_at_ms": ms(2026, 10, 25, 9, 0),
        "text": "【某银行】您账户1234于10月25日09:00存入3145元,可用余额12000元。附言：报销。"}).json()["transaction"]
    done = client.post(f"/trips/{trip['id']}/settle", json={"transaction_id": inc["id"]}).json()
    assert done["settled"] is True and float(done["allowance_gain"]) == 1160.0 and float(done["difference"]) == 0.0
    assert float(client.get("/accounts").json()["receivable"]) == 0.0
    sep = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()     # 20 Sep - 19 Oct
    octo = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()    # 20 Oct - 19 Nov
    assert float(sep["total_expense"]) == 30.0 and float(sep["total_income"]) == 0.0
    assert float(octo["total_income"]) == 1160.0 and float(octo["total_expense"]) == 0.0              # only the allowances are income
    # undo works and brings everything back
    client.post(f"/trips/{trip['id']}/unsettle")
    assert float(client.get("/accounts").json()["receivable"]) == 3145.0


def test_business_income_is_exactly_the_allowances_even_if_the_payout_differs(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 1})
    _sms(client, "【某银行】您账户1234于10月3日10:00向支付宝外部商户-携程旅行网支出1000元,可用余额9000元。", 10, 0, day=3)
    trip = client.post("/trips", json={"name": "出差", "start_date": "2026-10-03", "end_date": "2026-10-04", "kind": "business",
                                       "daily_allowance": 180, "commute_allowance": 80}).json()
    # the company pays 2000: 1000 of it is the user's own money, income is the allowances 80 + 180 x 2 = 440, regardless
    inc = client.post("/ingest", json={"source": "sms", "title": "x", "posted_at_ms": ms(2026, 10, 20, 9, 0),
        "text": "【某银行】您账户1234于10月20日09:00存入2000元,可用余额11000元。"}).json()["transaction"]
    done = client.post(f"/trips/{trip['id']}/settle", json={"transaction_id": inc["id"]}).json()
    assert float(done["allowance_gain"]) == 440.0 and float(done["difference"]) == 2000.0 - (1000.0 + 440.0)
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_income"]) == 440.0 and float(s["total_expense"]) == 0.0
    listed = client.get("/transactions", params={"limit": 10}).json()
    flight = [t for t in listed if t["merchant"] and "携程" in t["merchant"]][0]
    assert flight["trip_kind"] == "business" and flight["trip_name"] == "出差"


def test_manual_tag_and_the_two_views_of_business_spending(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    flight = _sms(client, "【某银行】您账户1234于10月7日11:26向支付宝外部商户-携程旅行网支出748.95元,可用余额9000元。", 11, 26, day=7)["transaction"]
    _sms(client, "【某银行】您账户1234于10月8日12:00向美团外卖-某面馆支出30元,可用余额8970元。", 12, 0, day=8)
    # a business trip whose date range does NOT cover the flight: the user adds it by hand
    trip = client.post("/trips", json={"name": "出差", "start_date": "2026-10-12", "end_date": "2026-10-13", "kind": "business",
                                       "daily_allowance": 180, "commute_allowance": 80}).json()
    assert trip["count"] == 0
    after = client.post(f"/trips/{trip['id']}/add/{flight['id']}").json()
    assert float(after["total"]) == 748.95
    client.put("/trips/%d" % trip["id"], json={"name": "出差", "start_date": "2026-10-12", "end_date": "2026-10-14", "kind": "business",
                                              "daily_allowance": 180, "commute_allowance": 80})        # editing dates keeps the hand-picked one
    assert float(client.get("/trips").json()[0]["total"]) == 748.95
    # view A (default): the advance is not spending
    a = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()
    assert float(a["total_expense"]) == 30.0
    # view B: it is counted...
    b = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9, "include_business": True}).json()
    assert float(b["total_expense"]) == 778.95
    # ...and once reimbursed (748.95 + 80 + 180 x 3 = 1368.95 paid back) the trip cancels out
    inc = client.post("/ingest", json={"source": "sms", "title": "x", "posted_at_ms": ms(2026, 10, 15, 9, 0),
        "text": "【某银行】您账户1234于10月15日09:00存入1368.95元,可用余额9000元。"}).json()["transaction"]
    client.post(f"/trips/{trip['id']}/settle", json={"transaction_id": inc["id"]})
    b = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9, "include_business": True}).json()
    assert float(b["total_expense"]) == 30.0 - 620.0           # 30 meal + 748.95 advance - 1368.95 paid back
    assert any(c["category_name"] == "出差报销冲抵" for c in b["by_category"])
    x = client.get("/analysis/cycle", params={"year": 2026, "month": 9, "include_business": True}).json()
    assert float(x["total_expense"]) == 30.0 - 620.0
    y = client.get("/analysis/year", params={"year": 2026, "include_business": True}).json()
    assert y["months"]


def test_hema_goes_to_food(client):
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    r = _sms(client, "【某银行】您账户1234于10月7日12:00向支付宝-盒马支出29.9元,可用余额900元。", 12, 0, day=7)
    assert cats[r["transaction"]["category_id"]] == "餐饮"
