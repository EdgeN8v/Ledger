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


# Payment platforms and catch-all merchants: they stand for thousands of different shops (a Meituan payment can be a
# dinner or a shared bike), so correcting one of them must never become a rule for all.
GENERIC_CORES = {
    "微信", "微信支付", "支付宝", "财付通", "云闪付", "京东", "京东商城", "淘宝", "天猫", "拼多多", "拼多多平台商户", "抖音", "抖音支付",
    "美团", "美团支付", "美团平台商户", "美团商户", "美团商户/北京三快在线科技有限公司", "北京三快在线科技有限公司", "扫二维码付款",
}


# A payee that is just a person's name (a second-hand seller, a stall's collection code) says nothing about what was bought,
# so correcting one payment must never become a rule for the next: a surname plus one or two characters, not a brand.
_SURNAMES = ("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤饶尤庞聂冉"
             "欧阳司马上官诸葛慕容")
_NOT_PERSON = {"钱大妈", "孙大妈", "麦当劳", "金拱门", "高德", "龙湖", "万达", "汤姆熊", "田园牧歌"}
_SHOP_END = tuple("店馆行部司铺坊厅园院社站摊屋堂楼庄场吧")


def is_person(core: str) -> bool:
    return (2 <= len(core) <= 3 and all("一" <= c <= "鿿" for c in core) and core[0] in _SURNAMES
            and core not in _NOT_PERSON and not core.endswith(_SHOP_END))


def is_generic(core: str) -> bool:
    return len(core) < 2 or core in GENERIC_CORES or is_person(core)


def rule_applies(pattern: str, merchant: Optional[str]) -> bool:
    """A learned rule is about a SHOP: it applies when the merchant's bare shop name is that shop (or, for a long
    enough name, contains it) - never because the pattern happens to appear somewhere in a message."""
    core = shop_core(merchant)
    if not core or not pattern or is_generic(pattern):
        return False                       # "扫二维码付款" says nothing about the shop: it can never be a shop rule
    return pattern == core or (len(pattern) >= 4 and pattern in core)


# WeChat's "已扣费 ¥7.90" notice names neither shop nor card (pay-later, auto-renewals): what the user decides about one
# such payment is learned for all of them under this key
UNNAMED_DEDUCTION = "微信已扣费(无商户)"


def is_unnamed_deduction(merchant: Optional[str], text: Optional[str]) -> bool:
    return not (merchant or "").strip() and "已扣费" in (text or "")
