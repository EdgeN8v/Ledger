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
    client.put(f"/transactions/{ids[0]}", json={"category_id": cats["交通/酒店"]})
    for i in ids:
        t = [x for x in client.get("/transactions").json() if x["id"] == i][0]
        assert t["category_id"] == cats["交通/酒店"] and t["needs_review"] is False


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
    assert "交通/酒店" in names and "交通" not in names and "车辆" not in names and "家人" in names


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
    client.put(f"/transactions/{first['id']}", json={"category_id": cats["交通/酒店"]})
    later = _sms(client, "【建设银行】您账户1234于10月5日15:20向微信支付-某贸易有限公司支出6元,可用余额890元。", 15, 20, day=5)["transaction"]
    assert later["category_id"] == cats["交通/酒店"] and later["needs_review"] is False
    again = _sms(client, "【建设银行】您账户1234于10月6日15:20向支付宝-某贸易有限公司支出5元,可用余额880元。", 15, 20, day=6)["transaction"]
    assert again["category_id"] == cats["交通/酒店"]


def test_travel_agency_and_airline_purchases_are_travel_not_entertainment(client):
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    for shop in ("支付宝外部商户-携程旅行网", "支付宝-中国东方航空股份有限公司", "支付宝-中国南方航空", "支付宝-春秋航空"):
        r = _sms(client, f"【某银行】您账户1234于10月7日11:26向{shop}支出748.95元,可用余额900元。", 11, 26, day=7)
        assert cats[r["transaction"]["category_id"]] == "交通/酒店", shop


def test_business_trip_is_receivable_not_spending_until_paid_back(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    pay = lambda d, h, shop, amt: _sms(client, f"【某银行】您账户1234于10月{d}日{h}:00向{shop}支出{amt}元,可用余额9000元。", h, 0, day=d)
    pay(7, 11, "支付宝外部商户-携程旅行网", 1200)        # flights, booked before departure
    pay(9, 7, "微信支付-滴滴出行", 45)                    # taxi to the airport: the user's own money (the commute allowance covers it)
    pay(10, 12, "微信支付-某酒店", 680)                   # hotel
    pay(10, 20, "微信支付-某面馆", 60)                    # meals on the trip: also the user's own (daily allowance)
    pay(12, 21, "微信支付-出租车", 60)                    # taxi home
    pay(15, 12, "微信支付-某面馆", 30)                    # ordinary life after the trip
    trip = client.post("/trips", json={"name": "北京出差", "start_date": "2026-10-07", "end_date": "2026-10-12", "kind": "business",
                                       "daily_allowance": 180, "commute_allowance": 80}).json()
    assert trip["count"] == 2 and float(trip["total"]) == 1880.0             # only the flight and the hotel
    assert trip["days"] == 6 and float(trip["allowance_total"]) == 80 + 180 * 6
    assert float(trip["expected_reimbursement"]) == 1880.0 + 1160.0
    # spending = taxis + meals only (45 + 60 + 60 + 30); the advance is not spending
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()
    assert float(s["total_expense"]) == 195.0
    acc = client.get("/accounts").json()
    assert float(acc["receivable"]) == 3040.0 and "北京出差" in acc["receivable_trips"][0]
    inc = client.post("/ingest", json={"source": "sms", "title": "x", "posted_at_ms": ms(2026, 10, 25, 9, 0),
        "text": "【某银行】您账户1234于10月25日09:00存入3040元,可用余额12000元。附言：报销。"}).json()["transaction"]
    done = client.post(f"/trips/{trip['id']}/settle", json={"transaction_id": inc["id"]}).json()
    assert done["settled"] is True and float(done["allowance_gain"]) == 1160.0 and float(done["difference"]) == 0.0
    assert float(client.get("/accounts").json()["receivable"]) == 0.0
    sep = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 9}).json()
    octo = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(sep["total_expense"]) == 195.0 and float(sep["total_income"]) == 0.0
    assert float(octo["total_income"]) == 1160.0 and float(octo["total_expense"]) == 0.0              # the allowances are the income
    client.post(f"/trips/{trip['id']}/unsettle")
    assert float(client.get("/accounts").json()["receivable"]) == 3040.0

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
    # a business trip whose date range (even with the booking lead) does NOT cover the flight: the user adds it by hand
    trip = client.post("/trips", json={"name": "出差", "start_date": "2026-11-05", "end_date": "2026-11-06", "kind": "business",
                                       "daily_allowance": 180, "commute_allowance": 80}).json()
    assert trip["count"] == 0
    after = client.post(f"/trips/{trip['id']}/add/{flight['id']}").json()
    assert float(after["total"]) == 748.95
    client.put("/trips/%d" % trip["id"], json={"name": "出差", "start_date": "2026-11-05", "end_date": "2026-11-07", "kind": "business",
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
    biz = [c for c in b["by_category"] if c["category_name"] == "出差垫付"]
    assert biz and float(biz[0]["total"]) == 748.95 - 1368.95            # its own line, net of the money paid back
    assert not any(c["category_name"] == "交通/酒店" for c in b["by_category"])   # not mixed into the road category
    x = client.get("/analysis/cycle", params={"year": 2026, "month": 9, "include_business": True}).json()
    assert float(x["total_expense"]) == 30.0 - 620.0
    assert [t["merchant"] for t in client.get("/transactions", params={"business_only": True, "tx_type": "expense"}).json()] == ["支付宝外部商户-携程旅行网"]
    y = client.get("/analysis/year", params={"year": 2026, "include_business": True}).json()
    assert y["months"]


def test_hema_goes_to_food(client):
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    r = _sms(client, "【某银行】您账户1234于10月7日12:00向支付宝-盒马支出29.9元,可用余额900元。", 12, 0, day=7)
    assert cats[r["transaction"]["category_id"]] == "餐饮"


def test_a_trip_without_auto_tag_takes_only_what_the_user_adds(client):
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    flight = _sms(client, "【某银行】您账户1234于10月7日11:26向支付宝外部商户-携程旅行网支出748.95元,可用余额9000元。", 11, 26, day=7)["transaction"]
    _sms(client, "【某银行】您账户1234于10月7日12:30向美团外卖-某面馆支出30元,可用余额8000元。", 12, 30, day=7)
    trip = client.post("/trips", json={"name": "出差", "start_date": "2026-10-07", "end_date": "2026-10-09", "kind": "business",
                                       "auto_tag": False}).json()
    assert trip["count"] == 0                                    # the meal inside the date range is NOT swallowed
    assert client.post(f"/trips/{trip['id']}/add/{flight['id']}").json()["count"] == 1
    on = client.post("/trips", json={"name": "自动", "start_date": "2026-10-07", "end_date": "2026-10-07", "kind": "personal"}).json()
    assert on["count"] >= 1                                      # default behaviour unchanged for ordinary trips


def test_editing_a_trip_keeps_auto_tag_and_receivable_lists_its_items(client):
    trip = client.post("/trips", json={"name": "Demo business", "start_date": "2026-03-10", "end_date": "2026-03-11",
                                       "kind": "business", "daily_allowance": "100", "commute_allowance": "50",
                                       "auto_tag": False}).json()
    tx = client.post("/transactions", json={"amount": "800", "tx_type": "expense", "note": "Demo Air 机票",
                                            "occurred_at": "2026-03-10T08:00:00"}).json()
    assert client.post(f"/trips/{trip['id']}/add/{tx['id']}").status_code == 200
    # the user comes back a day later: dates change, the trip must not start auto-tagging
    r = client.put(f"/trips/{trip['id']}", json={"name": "Demo business", "start_date": "2026-03-10", "end_date": "2026-03-12",
                                                 "kind": "business", "daily_allowance": "100", "commute_allowance": "50"})
    assert r.status_code == 200 and r.json()["days"] == 3
    rec = client.get("/trips/receivable").json()
    assert len(rec) == 1 and rec[0]["trip_id"] == trip["id"]
    assert float(rec[0]["advanced"]) == 800 and float(rec[0]["allowance"]) == 350 and float(rec[0]["expected"]) == 1150
    assert [i["merchant"] for i in rec[0]["items"]] == ["Demo Air 机票"]


def test_bill_reminders_show_five_days_ahead_and_vanish_when_paid(client):
    from datetime import date
    from app.fixed import reminders
    items = [
        {"name": "Demo rent", "monthly": 4900, "remind": {"months": [3, 6, 9, 12], "before_days": 5, "amount": 14700}},
        {"name": "Demo node", "monthly": 138, "match_amount": [138], "remind": {"before_days": 5}},
        {"name": "Demo plain", "monthly": 10},
    ]
    never = lambda it: False
    assert reminders(items, date(2026, 11, 20), never) == []                               # too early
    got = {r["name"]: r for r in reminders(items, date(2026, 11, 26), never)}
    assert set(got) == {"Demo node"} and got["Demo node"]["days_left"] == 4                # rent is not due in November
    got = {r["name"]: r for r in reminders(items, date(2026, 12, 26), never)}
    assert set(got) == {"Demo rent", "Demo node"} and float(got["Demo rent"]["amount"]) == 14700
    assert [r["name"] for r in reminders(items, date(2026, 12, 28), lambda it: it["name"] == "Demo node")] == ["Demo rent"]
    assert client.get("/plan/reminders").status_code == 200


def test_business_trip_picks_up_a_ticket_booked_before_departure(client):
    _sms(client, "【某银行】您账户1234于10月10日20:00向支付宝外部商户-携程旅行网支出900元,可用余额9000元。", 20, 0, day=10)
    trip = client.post("/trips", json={"name": "Demo biz", "start_date": "2026-10-11", "end_date": "2026-10-12", "kind": "business"}).json()
    assert float(trip["total"]) == 900            # bought the day before departure


CSAIR = ("【南航】您的机票已于2026-10-07支付成功。乘机人张三，票号7840000000001， 10月08日 CZ1234航班经济舱，上海(上海虹桥国际机场) 航站楼T2 08:35-"
         "北京(北京大兴国际机场)  10:45。为保障您的出行，请于航班起飞前30分钟前完成乘机登记手续。")
AGENT_OUT = ("【某航旅】尊敬的客户：您已成功预定10月12日北京首都到上海虹桥T2，东航MU1000，21:00起飞-23:10到达，经济舱，乘机人张三(证件号码:110000000000000000)，"
             "票价：(票价1000 机建税100 燃油费100元)/人 8元服务费/人, 票号：7810000000002,建议您国内航班不少于90分钟到达机场。")
AGENT_SITE = ("【某航旅】尊敬的客户：您已成功预定10月09日上海虹桥T2到兰州T3，东航MU2000，07:40起飞-11:00到达，经济舱;10月09日兰州T3到嘉峪关，东航MU2001，14:30起飞-16:05到达，经济舱，"
              "乘机人张三(证件号码:110000000000000000)，票价：(票价900 机建税100 燃油费100元)/人 8元服务费/人, 票号：7810000000003,建议您。")


def _flight_sms(client, text, day=7, h=10):
    return client.post("/ingest", json={"source": "sms", "title": "x", "text": text, "posted_at_ms": ms(2026, 10, day, h, 0)}).json()


def _pay(client, merchant, amount, day=7):
    return _sms(client, f"【某银行】您账户1234于10月{day}日09:00向支付宝外部商户-{merchant}支出{amount}元,可用余额9000元。", 9, 0, day=day)


def test_flight_sms_parse():
    from app.flights import parse_flights
    f = parse_flights(CSAIR, datetime(2026, 10, 7, 10, 0))
    assert [(x["flight_no"], x["origin"], x["dest"], x["depart_at"].strftime("%m-%d %H:%M")) for x in f] == [("CZ1234", "上海", "北京", "10-08 08:35")]
    two = parse_flights(AGENT_SITE, datetime(2026, 10, 7, 10, 0))
    assert [(x["flight_no"], x["origin"], x["dest"]) for x in two] == [("MU2000", "上海", "兰州"), ("MU2001", "兰州", "嘉峪关")]
    assert float(two[0]["price"]) == 1108


def test_no_payment_no_trip_and_a_payment_makes_one(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    r = _flight_sms(client, CSAIR)
    assert r["status"] == "flight" and r["transaction"] is None            # a ticket is not money
    assert client.get("/trips").json() == []                                # nobody paid: nothing to record
    _pay(client, "南方航空", 748.95)                                         # the user pays for the ticket
    trips = client.get("/trips").json()
    assert len(trips) == 1 and trips[0]["kind"] == "business" and trips[0]["start_date"] == "2026-10-08" and trips[0]["name"] == "出差·北京"
    assert trips[0]["flights"] == ["10-08 08:35 CZ1234 上海→北京"] and float(trips[0]["total"]) == 748.95
    assert trips[0]["open_end"] is True and float(trips[0]["allowance_total"]) == 0     # way back not booked yet
    _flight_sms(client, AGENT_OUT, day=8)                                   # return ticket: closes the trip on the day it lands
    t = client.get("/trips").json()[0]
    assert t["end_date"] == "2026-10-12" and t["days"] == 5 and t["open_end"] is False and float(t["allowance_total"]) == 80 + 180 * 5
    assert _flight_sms(client, CSAIR)["status"] == "duplicate"
    assert len(client.get("/trips").json()) == 1


def test_a_ticket_payment_matched_by_price_and_deleting_the_trip_sticks(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    _flight_sms(client, AGENT_OUT.replace("10月12日北京首都到上海虹桥T2，东航MU1000", "10月09日上海虹桥T2到北京首都，东航MU1000"), day=7)
    assert client.get("/trips").json() == []
    _pay(client, "某旅行社", 1208)                                           # exactly the ticket price near the order day
    trip = client.get("/trips").json()[0]
    assert float(trip["total"]) == 1208
    assert client.delete(f"/trips/{trip['id']}").status_code == 204
    assert client.get("/trips").json() == []


def test_someone_elses_ticket_is_not_ours(client):
    client.put("/auth/ledger-settings", json={"owner_names": "李四"})
    _flight_sms(client, CSAIR)
    _pay(client, "南方航空", 700)
    assert client.get("/trips").json() == []


def test_editing_the_dates_of_a_flight_trip_sticks_and_long_stays_count_no_allowance(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    _flight_sms(client, CSAIR)
    _pay(client, "南方航空", 748.95)
    t = client.get("/trips").json()[0]
    client.put(f"/trips/{t['id']}", json={"name": t["name"], "start_date": "2026-10-08", "end_date": "2026-10-20", "kind": "business",
                                          "daily_allowance": 180, "commute_allowance": 80})
    _flight_sms(client, AGENT_OUT, day=8)                                    # would end the trip on the 12th
    t = client.get("/trips").json()[0]
    assert t["end_date"] == "2026-10-20" and t["open_end"] is False
    client.put(f"/trips/{t['id']}", json={"name": t["name"], "start_date": "2026-10-08", "end_date": "2026-11-20", "kind": "business",
                                          "daily_allowance": 180, "commute_allowance": 80})
    t = client.get("/trips").json()[0]
    assert t["long_trip"] is True and float(t["allowance_total"]) == 0


def test_old_flight_chains_do_not_become_trips(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    jul = AGENT_OUT.replace("10月12日北京首都到上海虹桥T2，东航MU1000", "07月18日上海虹桥T2到银川T3，上航FM9225")
    client.post("/ingest", json={"source": "sms", "title": "x", "text": jul, "posted_at_ms": ms(2026, 7, 14, 6, 10)})
    assert client.get("/trips").json() == []


def test_mileage_notes_are_not_failed_payments(client):
    r = _flight_sms(client, "【东方航空】尊敬的张三会员 :感谢您于2026-09-18 乘坐MU6676航班,舱位S ,累积里程积分736点,定级里程1614公里,定级次数1.0次,消费金额920元,详情请关注公众号。")
    assert r["status"] == "ignored"


def test_a_posting_booked_by_others_leaves_no_trace(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    _flight_sms(client, AGENT_SITE, day=3)
    _pay(client, "某超市", 25)                                                # ordinary spending is not evidence of anything
    assert client.get("/trips").json() == []
    assert client.get("/transactions").json()[0]["amount"] == "25.00" or float(client.get("/transactions").json()[0]["amount"]) == 25


def test_an_unpaid_auto_trip_from_an_older_version_is_cleaned_up(client):
    from app.database import SessionLocal
    from app import models
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    _flight_sms(client, CSAIR)
    _pay(client, "南方航空", 748.95)
    t = client.get("/trips").json()[0]
    # the payment disappears (e.g. it was deleted): no payment, no trip
    tx = client.get("/transactions").json()[0]
    client.delete(f"/transactions/{tx['id']}")
    assert client.get("/trips").json() == []


def test_a_random_travel_site_payment_is_no_evidence_for_a_ticket_booked_by_others(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    _flight_sms(client, AGENT_SITE, day=7)
    _pay(client, "携程旅行网", 146, day=7)                                    # the user paid something small at a travel site that day
    assert client.get("/trips").json() == []


def test_fixed_items_can_be_marked_paid_for_the_cycle_or_ahead(client):
    from app.fixed import marked_paid, reminders
    from datetime import date
    client.put("/auth/ledger-settings", json={"cycle_start_day": 20})
    client.put("/plan", json={"savings_target": "100", "fixed_items": [
        {"name": "Demo node", "monthly": 138, "tracked": True, "remind": {"before_days": 5}},
        {"name": "Demo parking", "monthly": 300, "tracked": True, "remind": {"before_days": 5}}]})
    st = client.get("/plan/status").json()
    assert {f["name"]: f["state"] for f in st["fixed_items"]} == {"Demo node": "waiting", "Demo parking": "waiting"}
    client.post("/plan/fixed-mark", json={"name": "Demo node", "paid": True})
    far = date.today().replace(year=date.today().year + 1).isoformat()
    client.post("/plan/fixed-mark", json={"name": "Demo parking", "paid_until": far})
    got = {f["name"]: f for f in client.get("/plan/status").json()["fixed_items"]}
    assert got["Demo node"]["state"] == "paid" and "手动" in got["Demo node"]["paid_note"]
    assert got["Demo parking"]["state"] == "paid" and got["Demo parking"]["paid_note"].startswith("已付到")
    client.post("/plan/fixed-mark", json={"name": "Demo node", "paid": False})
    assert {f["name"]: f["state"] for f in client.get("/plan/status").json()["fixed_items"]}["Demo node"] == "waiting"
    # paid in advance: the reminder is for the date it runs out, not for the month end
    it = {"name": "P", "monthly": 300, "remind": {"before_days": 5}, "paid_until": "2026-11-20"}
    never = lambda i: False
    assert reminders([it], date(2026, 10, 26), never) == []
    assert [r["name"] for r in reminders([it], date(2026, 11, 16), never)] == ["P"]
    assert reminders([dict(it, paid_cycles=["2026-10"])], date(2026, 11, 16), never, "2026-10") == []


def test_correcting_a_shop_fixes_all_its_old_transactions_and_categories_follow_the_users_order(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    _sms(client, "【某银行】您账户1234于10月5日12:00向支付宝外部商户-某某炸鸡腿支出12元,可用余额900元。", 12, 0, day=5)
    _sms(client, "【某银行】您账户1234于10月6日12:00向京东支付-京东商城-某某炸鸡腿支出13元,可用余额890元。", 12, 0, day=6)
    first = client.get("/transactions").json()
    for t in first:
        client.put(f"/transactions/{t['id']}", json={"category_id": cats["购物"], "needs_review": False})   # old, confidently wrong
    one = first[0]
    client.put(f"/transactions/{one['id']}", json={"category_id": cats["餐饮"]})
    assert {t["category_id"] for t in client.get("/transactions").json()} == {cats["餐饮"]}
    # categories: biggest spenders first, until the user drags them
    order = [c["name"] for c in client.get("/categories", params={}).json() if c["tx_type"] == "expense"]
    assert order[0] == "餐饮"
    ids = [c["id"] for c in client.get("/categories").json() if c["tx_type"] == "expense"]
    client.put("/categories/order", json={"tx_type": "expense", "ids": list(reversed(ids))})
    assert [c["id"] for c in client.get("/categories").json() if c["tx_type"] == "expense"] == list(reversed(ids))


def test_a_balance_in_the_title_is_not_the_amount():
    p = parse_message("alipay", "你的亲情卡剩余9203.06元", "某某使用你赠送的亲情卡消费8.90元", datetime(2026, 10, 7, 18, 41))
    assert p is not None and str(p.amount) == "8.90" and p.tx_type == "expense"


def _tx(client):
    return client.get("/transactions", params={"limit": 50}).json()


def test_hotels_are_lodging_and_noodle_wraps_are_food(client):
    _sms(client, "【某银行】您账户1234于10月6日12:00向支付宝外部商户-某某酒店管理有限公司支出300元,可用余额900元。", 12, 0)
    _sms(client, "【某银行】您账户1234于10月6日13:00向微信支付-某某驴肉火烧支出20元,可用余额880元。", 13, 0)
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    got = {t["merchant"]: cats[t["category_id"]] for t in _tx(client)}
    assert got["支付宝外部商户-某某酒店管理有限公司"] == "交通/酒店" and got["微信支付-某某驴肉火烧"] == "餐饮"


def test_a_platform_is_never_learned_and_a_rule_matches_the_shop_not_the_text(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    _sms(client, "【某银行】您账户1234于10月6日12:00向微信支付-美团支出137.5元,可用余额900元。", 12, 0)
    _sms(client, "【某银行】您账户1234于10月6日12:05向美团支付-美团骑行支出0.99元,可用余额899元。", 12, 5)
    rows = {t["merchant"]: t for t in _tx(client)}
    big, bike = rows["微信支付-美团"], rows["美团支付-美团骑行"]
    client.put(f"/transactions/{big['id']}", json={"category_id": cats["人情"]})         # correcting the dinner...
    rows = {t["merchant"]: t for t in _tx(client)}
    assert rows["微信支付-美团"]["category_id"] == cats["人情"]
    assert rows["美团支付-美团骑行"]["category_id"] == bike["category_id"]                  # ...leaves the bike alone
    assert client.get("/categories").status_code == 200
    # and no rule was created for a platform: the next Meituan payment is judged on its own
    _sms(client, "【某银行】您账户1234于10月7日12:00向微信支付-美团支出9元,可用余额800元。", 12, 0, day=7)
    nxt = [t for t in _tx(client) if t["amount"] == "9.00"][0]
    assert nxt["category_id"] != cats["人情"] or nxt["needs_review"] is True


def test_the_second_report_of_a_shop_gets_the_learned_category_even_when_the_first_had_no_name(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    _sms(client, "【某银行】您账户1234于10月5日12:00向支付宝外部商户-某某专车支出20元,可用余额900元。", 12, 0, day=5)
    first = _tx(client)[0]
    client.put(f"/transactions/{first['id']}", json={"category_id": cats["交通/酒店"]})
    # a wallet notification arrives first (no shop name, so a guess), then the bank SMS brings the name
    client.post("/ingest", json={"source": "alipay", "title": "支付宝", "text": "你有一笔31.83元的支出，点此查看详情。", "posted_at_ms": ms(2026, 10, 6, 21, 17)})
    _sms(client, "【某银行】您账户1234于10月6日21:17向支付宝外部商户-某某专车支出31.83元,可用余额800元。", 21, 17)
    row = [t for t in _tx(client) if t["amount"] == "31.83"]
    assert len(row) == 1 and row[0]["category_id"] == cats["交通/酒店"] and row[0]["needs_review"] is False


def test_a_family_card_payment_announced_twice_is_one_record(client):
    t0 = ms(2026, 10, 6, 13, 2)
    client.post("/ingest", json={"source": "alipay", "title": "你的亲情卡剩余9203.06元", "text": "某某使用你赠送的亲情卡消费14.00元", "posted_at_ms": t0})
    client.post("/ingest", json={"source": "alipay", "title": "支付宝", "text": "你有一笔14.00元的支出，点此查看详情。", "posted_at_ms": t0 + 1000})
    _sms(client, "【某银行】您账户1234于10月6日13:02向支付宝外部商户-某某馄饨支出14元,可用余额900元。", 13, 2)
    rows = [t for t in _tx(client) if t["amount"] == "14.00"]
    assert len(rows) == 1 and rows[0]["merchant"] == "支付宝外部商户-某某馄饨"


def test_chat_messages_are_not_kept(client):
    from app.database import SessionLocal
    from app import models
    client.post("/ingest", json={"source": "wechat", "title": "某人", "text": "今晚吃什么", "posted_at_ms": ms(2026, 10, 6, 13, 2)})
    with SessionLocal() as db:
        ev = db.query(models.RawEvent).order_by(models.RawEvent.id.desc()).first()
        assert ev.status == "ignored" and ev.text == "" and ev.title == ""


def test_update_downloads_need_the_secret_link(client, tmp_path, monkeypatch):
    (tmp_path / "App-1.apk").write_bytes(b"apk")
    monkeypatch.setenv("LEDGER_UPDATE_DIR", str(tmp_path))
    monkeypatch.setenv("LEDGER_UPDATE_TOKEN", "s3cret")
    assert client.get("/update/s3cret/App-1.apk").content == b"apk"
    assert client.get("/update/wrong/App-1.apk").status_code == 404
    assert client.get("/update/s3cret/..%2Fetc.apk").status_code == 404
    monkeypatch.delenv("LEDGER_UPDATE_TOKEN")
    assert client.get("/update/s3cret/App-1.apk").status_code == 404


def test_my_car_category_and_its_report(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    assert "我的车" in cats and "交通/酒店" in cats
    _sms(client, "【某银行】您账户1234于10月5日12:00向微信支付-中国石化某某加油站支出300元,可用余额900元。", 12, 0, day=5)
    _sms(client, "【某银行】您账户1234于10月6日12:00向微信支付-某某汽修保养支出500元,可用余额400元。", 12, 0, day=6)
    _sms(client, "【某银行】您账户1234于10月6日13:00向支付宝外部商户-高德打车支出30元,可用余额370元。", 13, 0, day=6)
    got = {t["merchant"]: t["category_id"] for t in client.get("/transactions", params={"limit": 50}).json()}
    assert got["微信支付-中国石化某某加油站"] == cats["我的车"] and got["微信支付-某某汽修保养"] == cats["我的车"]
    assert got["支付宝外部商户-高德打车"] == cats["交通/酒店"]
    rep = client.get("/analysis/car", params={"range": "year"}).json()
    by = {b["key"]: b for b in rep["buckets"]}
    assert float(by["fuel"]["total"]) == 300 and float(by["repair"]["total"]) == 500 and float(rep["core_total"]) == 800
    assert rep["fuel"]["count"] == 1 and float(rep["fuel"]["avg"]) == 300


def test_a_parking_fixed_item_counts_as_parking_in_the_car_report(client):
    client.put("/plan", json={"savings_target": "100", "fixed_items": [{"name": "停车费", "monthly": 300, "category": "我的车", "match_amount": [300], "match_merchants": ["某某物业"]}]})
    _sms(client, "【某银行】您账户1234于10月5日12:00向微信支付-某某物业服务有限公司支出300元,可用余额900元。", 12, 0, day=5)
    rep = client.get("/analysis/car", params={"range": "year"}).json()
    by = {b["key"]: b for b in rep["buckets"]}
    assert float(by["parking"]["total"]) == 300 and float(by["other"]["total"]) == 0


def test_an_unreadable_ticket_message_is_kept_so_a_rule_can_be_written_from_it(client):
    odd = "【某平台】您的订单已出票成功，国航CA1234航班，10月12日 北京-上海，请按时乘机。"
    r = _flight_sms(client, odd)
    assert r["status"] == "failed"
    assert client.get("/ingest/failed").json()[0]["text"].startswith("【某平台】您的订单已出票成功")
    ad = _flight_sms(client, "【某航空】升级公务舱优惠，航班CA1234机票立减200元", day=8)
    assert ad["status"] == "ignored"


def test_a_travel_site_ticket_message_marks_the_way_back_as_booked_without_inventing_a_date(client):
    client.put("/auth/ledger-settings", json={"owner_names": "张三"})
    _flight_sms(client, CSAIR)
    _pay(client, "南方航空", 748.95)
    t = client.get("/trips").json()[0]
    assert t["open_end"] is True and t["return_booked"] is None
    r = _flight_sms(client, "【携程】订单尾号38874087，首都-虹桥，机票出票成功，出行必读：https://sdp.example.com/x ，拒收请回复R", day=9)
    assert r["status"] == "flight"
    t = client.get("/trips").json()[0]
    assert t["open_end"] is True and t["return_booked"] == "2026-10-09" and float(t["allowance_total"]) == 0
    # the user fills in the day he is back: the hint goes away and the allowance is counted
    client.put(f"/trips/{t['id']}", json={"name": t["name"], "start_date": "2026-10-08", "end_date": "2026-10-13", "kind": "business",
                                          "daily_allowance": 180, "commute_allowance": 80})
    t = client.get("/trips").json()[0]
    assert t["return_booked"] is None and t["open_end"] is False and float(t["allowance_total"]) == 80 + 180 * 6
    # a ticket that is not a way home changes nothing
    other = _flight_sms(client, "【携程】订单尾号1，上海-北京，机票出票成功，出行必读", day=9, h=11)
    assert other["status"] == "flight"


def test_importing_a_statement_adds_once_and_skips_what_sms_already_saw(client):
    from app.database import SessionLocal
    from app.importer import import_rows
    from app import models
    _sms(client, "【建设银行】您账户1234于10月6日21:25向淘宝支出2元,可用余额800.00元。", 21, 25)
    rows = [
        {"key": "1", "when": datetime(2026, 10, 6, 21, 25), "amount": "2", "tx_type": "expense", "merchant": "淘宝", "account": "建设银行1234"},
        {"key": "2", "when": datetime(2026, 9, 10, 16, 44), "amount": "300", "tx_type": "expense", "merchant": "某某汽修·机油", "note": "机油保养", "account": "某银行9545", "category": "我的车", "split": True},
        {"key": "3", "when": datetime(2026, 9, 10, 16, 44), "amount": "200", "tx_type": "expense", "merchant": "某某汽修·刹车片", "account": "某银行9545", "category": "我的车", "split": True},
        {"key": "4", "when": datetime(2026, 9, 25, 15, 2), "amount": "4000", "tx_type": "income", "merchant": "公积金提取", "account": "某银行9545", "category": "公积金"},
    ]
    with SessionLocal() as db:
        ledger = db.query(models.Ledger).first()
        user = db.query(models.User).first()
        first = import_rows(db, ledger.id, user.id, rows, "demo")
        second = import_rows(db, ledger.id, user.id, rows, "demo")
    assert first == {"added": 3, "duplicate": 0, "seen": 1, "transfers_paired": 0} and second["added"] == 0 and second["duplicate"] == 4
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    got = {t["merchant"]: cats.get(t["category_id"]) for t in client.get("/transactions", params={"limit": 50}).json()}
    assert got["某某汽修·机油"] == "我的车" and got["公积金提取"] == "公积金"
    rep = client.get("/analysis/car", params={"range": "12m"}).json()
    by = {b["key"]: float(b["total"]) for b in rep["buckets"]}
    assert by["repair"] == 500                                   # oil and brake pads are upkeep
    # a category the user chose is not re-filed by a fixed item that happens to match
    client.put("/plan", json={"fixed_items": [{"name": "Mum", "monthly": 4000, "category": "家人", "match_amount": [4000]}]})
    from app.maintenance import apply_fixed_items
    with SessionLocal() as db:
        apply_fixed_items(db)
    cats2 = {c["id"]: c["name"] for c in client.get("/categories").json()}
    assert {t["merchant"]: cats2.get(t["category_id"]) for t in client.get("/transactions", params={"limit": 50}).json()}["公积金提取"] == "公积金"


def test_imported_rows_are_filed_like_live_ones_and_own_transfers_pair_up(client):
    from app.database import SessionLocal
    from app.importer import import_rows
    from app import models
    _sms(client, "【建设银行】您账户8714于9月26日20:00存入5000元,可用余额6000.00元。", 20, 0, day=26)
    rows = [
        {"key": "a", "when": datetime(2026, 10, 1, 12, 0), "amount": "30", "tx_type": "expense", "merchant": "微信支付-某某火烧", "account": "某银行9545"},
        {"key": "b", "when": datetime(2026, 10, 6, 12, 0), "amount": "50", "tx_type": "expense", "merchant": "微信支付-某某未知小店", "account": "某银行9545"},
        {"key": "t", "when": datetime(2026, 10, 6, 20, 0), "amount": "5000", "tx_type": "expense", "merchant": "本人", "account": "某银行9545", "is_transfer": True},
    ]
    with SessionLocal() as db:
        ledger = db.query(models.Ledger).first(); user = db.query(models.User).first()
        got = import_rows(db, ledger.id, user.id, rows, "t2")
    assert got["added"] == 3
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    by = {t["merchant"]: t for t in client.get("/transactions", params={"limit": 50}).json()}
    assert cats[by["微信支付-某某火烧"]["category_id"]] == "餐饮" and by["微信支付-某某火烧"]["needs_review"] is False
    assert by["微信支付-某某未知小店"]["needs_review"] is True               # a guess is flagged, as for a live payment


def test_an_unnamed_wechat_deduction_is_learned_but_a_named_one_still_follows_its_shop(client):
    cats = {c["name"]: c["id"] for c in client.get("/categories").json()}
    client.post("/ingest", json={"source": "wechat", "title": "微信支付", "text": "已扣费¥7.90", "posted_at_ms": ms(2026, 10, 6, 8, 30)})
    first = [t for t in client.get("/transactions", params={"limit": 50}).json() if t["amount"] == "7.90"][0]
    assert first["needs_review"] is True
    client.put(f"/transactions/{first['id']}", json={"category_id": cats["购物"]})
    client.post("/ingest", json={"source": "wechat", "title": "微信支付", "text": "已扣费¥9.90", "posted_at_ms": ms(2026, 10, 7, 8, 30)})
    nxt = [t for t in client.get("/transactions", params={"limit": 50}).json() if t["amount"] == "9.90"][0]
    assert nxt["category_id"] == cats["购物"] and nxt["needs_review"] is False
    # when the bank SMS for the same deduction brings a shop name, the shop decides
    client.post("/ingest", json={"source": "wechat", "title": "微信支付", "text": "已扣费¥20.00", "posted_at_ms": ms(2026, 10, 8, 9, 0)})
    _sms(client, "【某银行】您账户1234于10月8日09:00向微信支付-某某驴肉火烧支出20元,可用余额900元。", 9, 0, day=8)
    row = [t for t in client.get("/transactions", params={"limit": 50}).json() if t["amount"] == "20.00"][0]
    assert row["category_id"] == cats["餐饮"]


def test_a_wallet_notice_no_bank_sms_joined_is_set_aside_and_counts_again_if_the_sms_comes_late(client):
    from datetime import timedelta
    from app.database import SessionLocal
    from app import models
    from app.maintenance import sweep_wallet_only, WALLET_ONLY_NOTE
    client.post("/ingest", json={"source": "wechat", "title": "微信支付", "text": "已扣费¥7.90", "posted_at_ms": ms(2026, 10, 6, 8, 30)})
    with SessionLocal() as db:
        assert sweep_wallet_only(db) == 0                      # too fresh: the SMS may still be on its way
        for x in db.query(models.Transaction).all():
            x.created_at = x.created_at - timedelta(hours=1)
        db.commit()
        assert sweep_wallet_only(db) == 1
    t = [x for x in client.get("/transactions", params={"limit": 50}).json() if x["amount"] == "7.90"][0]
    assert t["is_transfer"] is True and t["note"] == WALLET_ONLY_NOTE
    s = client.get("/transactions/summary/monthly", params={"year": 2026, "month": 10}).json()
    assert float(s["total_expense"]) == 0
    _sms(client, "【某银行】您账户1234于10月6日08:30向微信支付-某某小店支出7.9元,可用余额900元。", 8, 30, day=6)
    t = [x for x in client.get("/transactions", params={"limit": 50}).json() if x["amount"] == "7.90"]
    assert len(t) == 1 and t[0]["is_transfer"] is False and t[0]["merchant"] == "微信支付-某某小店"


def test_family_card_filter_and_hospital_payments_are_medical(client):
    from app.database import SessionLocal
    from app import models
    _sms(client, "【某银行】您账户1234于10月6日12:00向微信支付-上海市某某中心医院缴费支出40元,可用余额900元。", 12, 0, day=6)
    _sms(client, "【某银行】您账户1234于10月6日13:00向支付宝-淘宝-某某支出99元,可用余额800元。", 13, 0, day=6)
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    rows = {t["merchant"]: t for t in client.get("/transactions", params={"limit": 50}).json()}
    assert cats[rows["微信支付-上海市某某中心医院缴费"]["category_id"]] == "医疗"
    with SessionLocal() as db:
        t = db.get(models.Transaction, rows["支付宝-淘宝-某某"]["id"]); t.family_card = True; db.commit()
    assert [x["amount"] for x in client.get("/transactions", params={"kind": "family"}).json()] == ["99.00"]
    assert [x["amount"] for x in client.get("/transactions", params={"kind": "mine"}).json()] == ["40.00"]
    cyc = client.get("/analysis/cycle").json()
    assert float(cyc["family_total"]) == 99


def test_the_user_can_say_which_kind_of_car_cost_a_payment_is(client):
    _sms(client, "【某银行】您账户1234于10月5日12:00向微信支付-某某汽车服务中心支出300元,可用余额900元。", 12, 0, day=5)
    rep = client.get("/analysis/car", params={"range": "year"}).json()
    item = [i for b in rep["buckets"] for i in b["items"] if float(i["amount"]) == 300][0]
    assert {b["key"]: b for b in rep["buckets"]}["repair"]["desc"]
    client.put(f"/transactions/{item['id']}", json={"car_bucket": "parts"})
    rep = client.get("/analysis/car", params={"range": "year"}).json()
    by = {b["key"]: b for b in rep["buckets"]}
    assert float(by["parts"]["total"]) == 300 and by["parts"]["items"][0]["manual"] is True and float(by["repair"]["total"]) == 0
    assert {c["key"] for c in rep["choices"]} >= {"fuel", "repair", "insurance", "other"}


def test_a_fixed_item_matches_its_payee_and_a_nameless_payment_is_not_guessed_as_food(client):
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    client.put("/plan", json={"fixed_items": [{"name": "Mum", "monthly": 1000, "category": "家人", "match_amount": [1000],
                                               "match_core": ["微信", "微信转账"], "match_core_part": ["妈妈"]}]})
    _sms(client, "【某银行】您账户1234于10月20日09:00向微信转账支出1000元,可用余额900元。", 9, 0, day=20)
    _sms(client, "【某银行】您账户1234于10月21日09:00向微信支付-某某汽修支出1000元,可用余额800元。", 9, 0, day=21)
    _sms(client, "【某银行】您账户1234于10月22日09:00向微信支付支出18元,可用余额700元。", 9, 0, day=22)
    got = {t["amount"]: cats[t["category_id"]] for t in client.get("/transactions", params={"limit": 20}).json()}
    assert got["1000.00"] in ("家人", "我的车")
    rows = client.get("/transactions", params={"limit": 20}).json()
    by = {t["merchant"]: cats[t["category_id"]] for t in rows}
    assert by["微信"] == "家人" and by["微信支付-某某汽修"] == "我的车"


def test_signed_in_users_can_fetch_the_latest_build(client, tmp_path, monkeypatch):
    (tmp_path / "Ledger-v1.apk").write_bytes(b"apk")
    (tmp_path / "version.json").write_text('{"versionCode": 11, "versionName": "1.0", "apk": "Ledger-v1.apk", "notes": ["x"]}', encoding="utf-8")
    monkeypatch.setenv("LEDGER_UPDATE_DIR", str(tmp_path))
    got = client.get("/app-update/latest")
    assert got.status_code == 200 and got.json()["versionCode"] == 11
    assert client.get("/app-update/apk").content == b"apk"


def test_car_upkeep_counts_down_from_the_odometer(client):
    client.post("/carcare/odometer", json={"km": 100000, "on": "2026-01-01"})
    client.post("/carcare/odometer", json={"km": 103000, "on": "2026-04-11"})        # 100 days -> 30 km/day
    client.post("/carcare/services", json={"item": "oil", "on": "2026-04-11", "km": 103000, "cost": "480"})
    client.put("/carcare/items/belt", json={"due_km": 130000})
    rep = client.get("/carcare").json()
    assert rep["odometer"]["per_day"] == 30.0
    by = {i["key"]: i for i in rep["items"]}
    assert by["oil"]["due_km"] == 111000 and by["oil"]["last"]["cost"] == "480.00"
    assert by["belt"]["due_km"] == 130000 and by["belt"]["status"] in ("ok", "soon")
    assert by["spark"]["status"] == "unknown"
    client.post("/carcare/odometer", json={"km": 112000})
    assert {i["key"]: i for i in client.get("/carcare").json()["items"]}["oil"]["status"] == "overdue"
    sid = client.get("/carcare").json()["history"][0]["id"]
    assert client.delete(f"/carcare/services/{sid}").status_code == 200


def test_sub_categories_are_guessed_learned_and_snacks_leave_shopping(client):
    cats = {c["name"]: c for c in client.get("/categories").json()}
    assert "住宿" not in cats and "日用" not in cats and cats["餐饮"]["subs"] == ["饭店", "外卖", "食材", "零食"]
    assert cats["交通/酒店"]["subs"] == ["交通", "酒店"]
    cid = {n: c["id"] for n, c in cats.items()}

    def pay(shop, h, note=None):
        r = _sms(client, f"【建设银行】您账户1234于10月6日{h}:00向{shop}支出30元,可用余额900元。", h, 0)
        return client.get("/transactions", params={"limit": 50}).json()[0] if r else None

    snack = pay("京东支付-京东商城-三只松鼠旗舰店", 10)
    assert cats["餐饮"]["id"] == snack["category_id"] and snack["sub"] == "零食"            # food bought on a shopping platform
    hotel = pay("微信支付-全季酒店", 11)
    assert hotel["category_id"] == cid["交通/酒店"] and hotel["sub"] == "酒店"
    rest = pay("微信支付-老乡鸡", 12)
    assert rest["category_id"] == cid["餐饮"] and rest["sub"] == "饭店"
    shirt = pay("支付宝-淘宝-某某服饰旗舰店", 13)
    assert shirt["category_id"] == cid["购物"] and shirt["sub"] == "服装"
    unknown = pay("支付宝-淘宝-某某贸易有限公司", 14)
    assert unknown["category_id"] == cid["购物"] and unknown["sub"] is None
    # the user decides what that shop is: remembered for its next payment
    assert client.put(f"/transactions/{unknown['id']}", json={"sub": "日用品"}).json()["sub"] == "日用品"
    again = pay("支付宝-淘宝-某某贸易有限公司", 15)
    assert again["sub"] == "日用品"
    assert client.put(f"/transactions/{again['id']}", json={"sub": "饭店"}).status_code == 400          # not a shopping sub
    got = client.get("/transactions/subs", params={"category_id": cid["购物"]}).json()
    assert {g["sub"] for g in got} >= {"服装", "日用品"}


def test_jd_statement_fills_in_what_was_bought(client):
    import base64, io, zipfile
    _sms(client, "【建设银行】您账户1234于10月6日10:00向京东支付-京东商城-某某旗舰店支出59.90元,可用余额900元。", 10, 0)
    _sms(client, "【建设银行】您账户1234于10月7日12:00向京东支付-京东商城-某某店支出88元,可用余额800元。", 12, 0, day=7)
    csv_text = ("导出信息：\n共：3笔记录\n\n交易时间,商户名称,交易说明,金额,收/付款方式,交易状态,收/支,交易分类,交易订单号,商家订单号,备注\n"
                "2026-10-06 10:00:05\t,京东平台商户,某某牌洗衣液 家庭装,59.90,建设银行储蓄卡(1234),交易成功,支出,清洁纸品,111\t,222\t, ,\n"
                "2026-10-07 12:00:00\t,京东平台商户,某某汽车玻璃水 2L,88.00,建设银行储蓄卡(1234),交易成功,支出,汽车用品,333\t,444\t, ,\n"
                "2026-10-08 12:00:00\t,京东平台商户,没有对上的东西,9.00,建设银行储蓄卡(1234),交易成功,支出,其他网购,555\t,666\t, ,\n")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("jd.csv", csv_text.encode("utf-8"))
    b64 = base64.b64encode(buf.getvalue()).decode()
    got = client.post("/import/jd", json={"zip_b64": b64, "password": "", "dry_run": False}).json()
    assert got["matched"] == 2 and got["unmatched"] == 1 and got["unmatched_list"][0]["title"].startswith("没有对上")
    cats = {c["id"]: c["name"] for c in client.get("/categories").json()}
    rows = {t["amount"]: t for t in client.get("/transactions").json()}
    assert rows["59.90"]["note"].startswith("某某牌洗衣液") and cats[rows["59.90"]["category_id"]] == "购物" and rows["59.90"]["sub"] == "日用品"
    assert cats[rows["88.00"]["category_id"]] == "我的车"
