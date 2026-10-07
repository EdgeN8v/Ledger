"""
Rule-based parsers for payment notifications and bank SMS.

parse_message() returns a Parsed transaction or None. Amount accuracy is the priority: the amount is
only ever taken from the number that directly follows a transaction keyword (支出/消费/付款...), never
"the biggest number in the text", so balances and limits cannot be mistaken for the payment.

Anything that looks financial but matches no template is reported by looks_financial() so the caller
can store it as "failed" and a new template can be written from the real sample.
"""
import re
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Optional

AMT = r"(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)"
OUT_KW = ("支出", "消费", "转出", "扣款", "取现", "支付", "付款")
IN_KW = ("收入", "转入", "存入", "入账", "收款", "到账")
ALL_KW = OUT_KW + IN_KW

_NOISE = re.compile(r"验证码|动态码|校验码|登录|密码|退订|还款提醒|账单日")
_GROUP_PREFIX = re.compile(r"^\s*\[\d+条\]\s*")
_REFUND = re.compile(r"退款|退货|退还|退回|已退")


@dataclass
class Parsed:
    tx_type: str                    # "expense" | "income"
    amount: Decimal
    merchant: Optional[str]
    channel: str                    # "bank" | "wechat" | "alipay"
    origin: str                     # which source produced it, used for cross-source merging
    account_hint: Optional[str] = None
    occurred_at: Optional[datetime] = None   # local naive time if the message states one
    balance: Optional[Decimal] = None        # account balance after this transaction, when the SMS states it
    is_refund: bool = False
    is_transfer: bool = False                # e.g. a credit-card repayment: money moved, nothing spent


def _money(s: str) -> Decimal:
    return Decimal(s.replace(",", ""))


_LONG_DIGITS = re.compile(r"\d{12,}")


def clean_text(text: str) -> str:
    """Strip the SMS app's running counter and mask long digit runs (ID / account numbers that some bank
    memos echo back) so they are never stored."""
    text = _GROUP_PREFIX.sub("", text or "").strip()
    return _LONG_DIGITS.sub(lambda m: m.group()[:3] + "***" + m.group()[-2:], text)


_clean = clean_text


def looks_financial(text: str) -> bool:
    text = _clean(text)
    if _NOISE.search(text):
        return False
    kw_alt = "|".join(ALL_KW)
    # a keyword right before an amount ("支出19.9元", "转出资金0.01元"), not just both appearing in an ad
    no_digit_gap = r"[^0-9\n]{0,6}"
    pattern = "(?:" + kw_alt + ")" + no_digit_gap + AMT + r"\s*元|[¥￥]\s*" + AMT
    return bool(re.search(pattern, text))


def _bank_sms(text: str, posted_at: datetime) -> Optional[Parsed]:
    bank = re.search(r"[【\[](.{2,12}?)[】\]]", text)
    if not bank:
        return None
    kw_alt = "|".join(ALL_KW)
    m = re.search(rf"(?P<kw>{kw_alt})(?:\([^)]*\))?(?:人民币|资金)?\s*{AMT}\s*元", text)
    if not m:
        return None
    kw = m.group("kw")
    amount = _money(m.group(2))

    occurred = None
    t = re.search(r"(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2})", text)
    if t:
        mon, day, hh, mm = map(int, t.groups())
        year = posted_at.year - 1 if mon > posted_at.month + 1 else posted_at.year
        try:
            occurred = datetime(year, mon, day, hh, mm)
        except ValueError:
            occurred = None

    merchant = None
    if t:
        mid = text[t.end():m.start()]
        if mid.lstrip("分,，。:： ").startswith("向您"):
            # "<payer>于10月6日12:00向您账户1234存入/消费退货存入...": the counterparty comes BEFORE the date
            merchant = re.sub(r"[于\s]+$", "", text[bank.end():t.start()]).strip() or None
        else:
            mid = re.sub(r"^[分,，。:：\s]*(?:向|在|于|通过)?", "", mid)
            mid = re.sub(r"(?:的)?(?:充值|消费|付款|转账|支付)?$", "", mid).strip(" ,，。")
            merchant = mid or None
    other = re.search(r"对方户名[:：]\s*([^\s,，。;；]{1,30})", text)
    if other:
        merchant = other.group(1)

    acct = re.search(r"(?:账户|尾号|卡号?)\s*\*?(\d{4})", text)
    bal = re.search(rf"余额[^0-9]{{0,6}}{AMT}", text)
    return Parsed(
        tx_type="income" if kw in IN_KW else "expense",
        amount=amount,
        merchant=merchant,
        channel="bank",
        origin=f"sms:{bank.group(1)}",
        account_hint=f"{bank.group(1)}{acct.group(1)}" if acct else bank.group(1),
        occurred_at=occurred,
        balance=_money(bal.group(1)) if bal else None,
        is_refund=kw in IN_KW and _REFUND.search(text) is not None,
        is_transfer="信用卡还款" in text,
    )


def _direction(text: str) -> Optional[str]:
    if any(k in text for k in ("收款", "收到", "已收", "到账", "入账")):
        return "income"
    if any(k in text for k in ("付款", "支付", "消费", "支出", "转账给", "已转")):
        return "expense"
    return None


def _wallet(text: str, title: str, channel: str) -> Optional[Parsed]:
    """WeChat Pay / Alipay payment notifications. Templates are written from general knowledge of the
    wording and are refined from real captured samples (unmatched ones are stored as 'failed')."""
    blob = f"{title} {text}"
    direction = _direction(blob)
    if not direction:
        return None
    m = re.search(rf"[¥￥]\s*{AMT}|{AMT}\s*元", blob)
    if not m:
        return None
    amount = _money(m.group(1) or m.group(2))
    merchant = None
    for pat in (r"向\s*([^\s,，。:：]{1,30}?)\s*(?:付款|转账|支付)", r"(?:收款方|商户|商家)[:：]\s*([^\s,，。]{1,30})",
                r"在\s*([^\s,，。]{1,30}?)\s*(?:消费|付款|支付)"):
        mm = re.search(pat, blob)
        if mm:
            merchant = mm.group(1)
            break
    return Parsed(tx_type=direction, amount=amount, merchant=merchant, channel=channel, origin=channel,
                  is_refund=direction == "income" and _REFUND.search(blob) is not None)


def parse_message(source: str, title: str, text: str, posted_at: datetime) -> Optional[Parsed]:
    title = title or ""
    text = _clean(text)
    if not text or _NOISE.search(text):
        return None
    if source == "sms":
        return _bank_sms(text, posted_at)
    if source == "wechat":
        if "微信支付" in title or "微信支付" in text[:12] or "红包" in title or "转账" in title:
            return _wallet(text, title, "wechat")
        return None
    if source == "alipay":
        return _wallet(text, title, "alipay")
    return None
