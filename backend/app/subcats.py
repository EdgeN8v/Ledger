"""Sub-categories: shopping (electronics / daily goods / clothes), food (restaurant / delivery / groceries / snacks) and
travel (transport / hotel). A transaction keeps its category as before; `sub` says which kind inside it.

What the user taught wins (a learned rule per shop); then keywords in the shop name and the order text; a food category
falls back to "restaurant" (the commonest). Shopping that cannot be told stays without a sub, so the app can ask.
A shopping item that is plainly food (a snack, groceries bought on JD) is filed under food instead.
"""
import re
from typing import Optional

from sqlalchemy import event, select
from sqlalchemy.orm import Session

TRAVEL = "交通/酒店"
SUBS = {
    "购物": ["数码", "日用", "服装", "其他"],
    "餐饮": ["饭店", "外卖", "食材", "零食"],
    TRAVEL: ["交通", "酒店"],
    "狗狗": ["狗粮", "狗砂", "零食", "玩具", "医疗", "其他"],
}

_SNACK = ("零食,坚果,饼干,糖果,巧克力,辣条,薯片,果干,肉干,蜜饯,瓜子,牛奶,酸奶,饮料,矿泉水,咖啡,奶茶,速溶,茶叶,三只松鼠,良品铺子,百草味,来伊份,盐津铺子,卫龙,洽洽,"
          "好想来,零食很忙,蜜雪冰城,茶百道,古茗,喜茶,奈雪,霸王茶姬,沪上阿姨,瑞幸,星巴克,库迪,冰淇淋,甜品,冷饮,饮品,面包,蛋糕,烘焙,好利来,糕点,零嘴,"
          "便利店,全家,便利蜂,罗森,7-ELEVEN,711,雪糕,冰棍,果汁,可乐,啤酒").split(",")
_GROCERY = ("超市,买菜,菜市场,菜场,农贸,生鲜,盒马,叮咚,小象,永辉,沃尔玛,山姆,大润发,物美,家乐福,联华,华润万家,百联,每日优鲜,水果,果切,百果园,蔬菜,肉铺,"
            "粮油,大米,面条,食用油,调料,酱油,牛排,海鲜,鸡蛋,卖场").split(",")
_DELIVERY = "外卖,饿了么,拉扎斯,美团外卖,三快在线".split(",")
_RESTAURANT = ("麦当劳,肯德基,KFC,必胜客,汉堡王,海底捞,火锅,烧烤,面馆,饺子,米线,麻辣烫,小吃,餐厅,饭店,食堂,快餐,拉面,披萨,寿司,串串,粥,沙县,黄焖鸡,老乡鸡,"
               "真功夫,华莱士,塔斯汀,酒楼,餐馆,食府,川菜,湘菜,烤肉,烤鱼,小炒,牛肉面,拌饭,自助,煎饼,馄饨,炒饭,盖浇饭,火烧,驴肉,肉夹馍,餐饮,"
               "肠粉,米粉,炒粉,凉粉,酸辣粉,肥肠粉,粉店,卷粉,饵粉,螺蛳粉,桂林米粉,过桥米线,板面,刀削面,热干面,炒面,汤包,小笼包,生煎包,包子铺,"
               "糖水,甜品店,小龙虾,炸串,关东煮,章鱼烧,寿喜锅,铁板烧").split(",")
_ELECTRONICS = ("数码,耳机,蓝牙,充电器,充电宝,数据线,键盘,鼠标,显示器,电脑,笔记本,平板,路由,相机,镜头,摄像,音箱,智能,手表,手环,电视,投影,硬盘,内存,固态,显卡,主板,"
                "手机,小米,华为,苹果,Apple,雷蛇,RAZER,罗技,联想,戴尔,大疆,韶音,绿联,倍思,插座,U盘,TF卡,电池,扩展坞,电器").split(",")
_DAILY = ("日用,纸巾,卫生纸,抽纸,湿巾,洗衣,洗发,沐浴,牙膏,牙刷,屈臣氏,名创优品,宜家,无印良品,家居,清洁,洗护,百货,生活用品,收纳,毛巾,浴巾,床上,四件套,被子,"
          "枕头,垃圾袋,厨房,水杯,保温杯,拖把,洗洁精,衣架,家纺,拖鞋,雨伞,剃须,卫生巾,尿不湿,餐具,砧板,一次性,快递,顺丰,寄件,超市,便利店").split(",")
_CLOTHES = ("服饰,服装,衣,裤,鞋,袜,内衣,内裤,文胸,T恤,外套,羽绒,衬衫,卫衣,毛衣,针织,风衣,夹克,西装,牛仔,优衣库,UNIQLO,ZARA,海澜之家,耐克,Nike,阿迪,adidas,安踏,"
            "李宁,波司登,GAP,森马,美特斯邦威,太平鸟,帽,围巾,手套").split(",")
_VIRTUAL = "会员,流量包,网盘,SVIP,代办,认证,代充,卡密,充值,激活码".split(",")
_PET = ("宠物,狗粮,犬粮,狗狗,狗砂,狗零食,狗玩具,狗牵引,狗链,遛狗,训犬,尿垫,尿片,冠能,比瑞吉,波奇,宠物医院").split(",")
_PET_MED = "医院,驱虫,疫苗,体检,诊疗,疫苗,外用药".split(",")
_PET_FOOD = "狗粮,犬粮,主粮,冻干,罐头".split(",")
_PET_PAD = "尿垫,尿片,砂,除臭".split(",")
_PET_SNACK = "零食,磨牙,洁齿,肉干,鸡胸,奖励".split(",")
_PET_TOY = "玩具,球,啃咬,绳结,飞盘".split(",")
_HOTEL = ("酒店,宾馆,民宿,客栈,旅馆,住宿,如家,汉庭,全季,亚朵,7天,锦江之星,维也纳,希尔顿,万豪,洲际,华住,度假村,公寓酒店,青年旅舍,快捷酒店,携程酒店,美团酒店,飞猪酒店").split(",")
# food that was bought on a shopping platform
_FOOD_ITEM_SNACK = [k for k in _SNACK if k not in ("便利店", "全家", "便利蜂", "罗森", "7-ELEVEN", "711", "咖啡", "星巴克", "瑞幸", "库迪")]
_FOOD_ITEM_GROCERY = [k for k in _GROCERY if k not in ("超市", "卖场", "农贸")]


def _has(text: str, words) -> bool:
    return any(w and w in text for w in words)


def _hay(merchant: Optional[str], note: Optional[str]) -> str:
    n = note if note and note != merchant else ""
    return f"{merchant or ''} {n}"


def is_pet(merchant: Optional[str], note: Optional[str]) -> bool:
    return _has(_hay(merchant, note), _PET)


def keyword_sub(cat_name: str, merchant: Optional[str], note: Optional[str]) -> Optional[str]:
    hay = _hay(merchant, note)
    if cat_name == "狗狗":
        for words, sub in ((_PET_MED, "医疗"), (_PET_PAD, "狗砂"), (_PET_TOY, "玩具"), (_PET_SNACK, "零食"), (_PET_FOOD, "狗粮")):
            if _has(hay, words):
                return sub
        return "其他"
    if cat_name == "餐饮":
        if _has(hay, ("饿了么", "拉扎斯", "美团外卖", "京东外卖", "外卖")):
            return "外卖"                                # a delivery platform is delivery, whatever was in the bag
        if _has(hay, _GROCERY):
            return "食材"
        if _has(hay, _SNACK):
            return "零食"
        if _has(hay, _DELIVERY):
            return "外卖"
        return "饭店"
    if cat_name == "购物":
        if _has(hay, _VIRTUAL):
            return "其他"
        if _has(hay, _ELECTRONICS):
            return "数码"
        if _has(hay, _DAILY):
            return "日用"
        if _has(hay, _CLOTHES):
            return "服装"
        return None
    if cat_name == TRAVEL:
        return "酒店" if _has(hay, _HOTEL) else "交通"
    return None


def food_instead_of_shopping(merchant: Optional[str], note: Optional[str]) -> Optional[str]:
    """A 'shopping' payment whose text is plainly food: the food sub it belongs to, else None."""
    hay = _hay(merchant, note)
    if _has(hay, _FOOD_ITEM_SNACK):
        return "零食"
    if _has(hay, _FOOD_ITEM_GROCERY):
        return "食材"
    return None


def guess(cat_name: str, merchant: Optional[str], note: Optional[str], rules=()) -> Optional[str]:
    """rules: (pattern, sub) learned from the user; the most specific shop name first."""
    from app.merchants import rule_applies
    if cat_name not in SUBS:
        return None
    for pattern, sub in sorted(rules, key=lambda r: -len(r[0] or "")):
        if sub in SUBS[cat_name] and rule_applies(pattern, merchant):
            return sub
    return keyword_sub(cat_name, merchant, note)


def rules_of(db: Session, ledger_id: int):
    from app import models
    return [(r.pattern, r.sub) for r in db.query(models.MerchantRule).filter(models.MerchantRule.ledger_id == ledger_id, models.MerchantRule.sub.isnot(None))]


def classify_all(db: Session) -> int:
    """Startup / catch-up pass: give every expense a sub that fits its category, and move shopping that is really food
    to food. Never touches a payment the user filed by hand (category_locked) except to fill an empty sub."""
    from app import models
    changed = 0
    for ledger in db.query(models.Ledger).all():
        cats = {c.id: c.name for c in db.query(models.Category).filter(models.Category.ledger_id == ledger.id)}
        by_name = {n: i for i, n in cats.items()}
        rules = rules_of(db, ledger.id)
        for t in db.query(models.Transaction).filter(models.Transaction.ledger_id == ledger.id, models.Transaction.tx_type == models.TxType.expense):
            name = cats.get(t.category_id)
            allowed = SUBS.get(name)
            if name in ("购物", "餐饮", "其他支出") and not t.category_locked and "狗狗" in by_name and is_pet(t.merchant, t.note):
                t.category_id, t.sub, changed = by_name["狗狗"], keyword_sub("狗狗", t.merchant, t.note), changed + 1
                continue
            if allowed is None:
                if t.sub is not None:
                    t.sub, changed = None, changed + 1
                continue
            if t.sub in allowed:
                continue
            if name in ("购物", "餐饮", "其他支出") and not t.category_locked and "狗狗" in by_name and is_pet(t.merchant, t.note):
                t.category_id, t.sub, changed = by_name["狗狗"], keyword_sub("狗狗", t.merchant, t.note), changed + 1
                continue
            if name == "购物" and not t.category_locked and "餐饮" in by_name:
                food = food_instead_of_shopping(t.merchant, t.note)
                if food:
                    t.category_id, t.sub, changed = by_name["餐饮"], food, changed + 1
                    continue
            new = guess(name, t.merchant, t.note, rules)
            if new != t.sub:
                t.sub, changed = new, changed + 1
    db.commit()
    return changed


def register() -> None:
    """New transactions get their sub (and the pet / shopping-that-is-food moves) whichever path creates them; the sub is
    also filled in when the category is only set after the first insert."""
    from app import models

    def fill(connection, t):
        if t.tx_type != models.TxType.expense or not t.category_id:
            return
        C, R = models.Category.__table__, models.MerchantRule.__table__
        row = connection.execute(select(C.c.name, C.c.ledger_id).where(C.c.id == t.category_id)).first()
        if not row:
            return
        name, ledger_id = row
        if not t.category_locked and name in ("购物", "餐饮", "其他支出") and is_pet(t.merchant, t.note):
            pid = connection.execute(select(C.c.id).where(C.c.ledger_id == ledger_id, C.c.name == "狗狗")).scalar()
            if pid and t.category_id != pid:
                t.category_id, t.sub = pid, keyword_sub("狗狗", t.merchant, t.note)
                return
        if name not in SUBS or t.sub is not None:
            return
        rules = [(p, s_) for p, s_ in connection.execute(select(R.c.pattern, R.c.sub).where(R.c.ledger_id == ledger_id, R.c.sub.isnot(None)))]
        if name == "购物" and not t.category_locked:
            food = food_instead_of_shopping(t.merchant, t.note)
            if food:
                fid = connection.execute(select(C.c.id).where(C.c.ledger_id == ledger_id, C.c.name == "餐饮")).scalar()
                if fid:
                    t.category_id, t.sub = fid, food
                    return
        t.sub = guess(name, t.merchant, t.note, rules)

    @event.listens_for(models.Transaction, "before_insert")
    def _before_insert(mapper, connection, t):  # noqa: ANN001
        fill(connection, t)

    @event.listens_for(models.Transaction, "before_update")
    def _before_update(mapper, connection, t):  # noqa: ANN001
        fill(connection, t)
