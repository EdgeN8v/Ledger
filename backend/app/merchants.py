"""One shop, many spellings: bank SMS prefix the shop with the payment channel ("微信支付-", "支付宝外部商户-" ...)
and sometimes cut long names short ("...有限公司上海*"). Learned rules use the bare shop name so the same place
is recognised whichever way you pay."""
import re
from typing import Optional

_PREFIX = re.compile(r"^(?:支付宝外部商户|支付宝|微信支付|财付通|京东支付|抖音支付|易宝支付|云闪付)[-/]")


def shop_core(merchant: Optional[str]) -> str:
    m = (merchant or "").strip()
    prev = None
    while prev != m:
        prev, m = m, _PREFIX.sub("", m)
    m = re.sub(r"^(?:天猫|淘宝|京东商城)[-/]", "", m)
    return m.strip("*… ").strip()
