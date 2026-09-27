"""图鉴采集：拉取 + 页面源文 + 落库闭环（票 #22 / #23）。

从数据源 Cargo 表拉全量**结构化**字段，再从页面源文取**只有散文里才有**的那半边，
转成图鉴条目按 nameEn upsert 进 `item` 表，写完应用侧立刻能读到。

- **结构化半边**（#22）：标签、生食状态效果、重量；
- **页面源文半边**（#23，见 `page_source.py`）：熟食覆写值 / 可烹饪判据、描述、成就。
  数值解析不出来时该条进失败明细，不静默退回公式；描述取不到时该条照常入库。

本票仍**不碰图标**（#24）：icon 列原值保留。description_zh / name_zh 是「对照表回填」的地盘，
一个字都不动 —— 这既是"已有中文名保留"的实现，也是"不碰中文名"的落实。

落库后删掉采集影响的三个缓存 key（图鉴全量 / 问答 / 题库）——「应用可见」的最后一环。
与 Java 采集的 `CrawlerServiceImpl.evictItemCache()` 同一口径；用户连对进度（`quiz:progress`）
是用户状态、不是缓存，不删。

用法::

    # 真跑一次全量采集并落库（结构化 + 页面源文，需要联网）
    python guide/tools/crawl_items.py

    # 只拉取 + 转换，与金标准比对，不写库（差异会被逐条列出，有差异时退出码非 0）
    python guide/tools/crawl_items.py --check

    # 离线复跑：Cargo 与页面源文都从本地读（#21 冻结的语料目录）
    python guide/tools/crawl_items.py --check \
        --items-file ../_baseline-corpus/cargo.json \
        --wikitext-dir ../_baseline-corpus

外部连接走环境变量（与 freeze_baseline.py 同一约定）：

    GUIDE_DB_HOST/PORT/USER/PASSWORD/NAME        默认 127.0.0.1:3306/root/空/peak_guide
    GUIDE_REDIS_HOST/PORT/PASSWORD/DB            默认 127.0.0.1:6379/无密码/0
"""

import argparse
import json
import os
import re
import socket
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import page_source

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_GOLDEN = REPO_ROOT / "guide" / "src" / "test" / "resources" / "crawler" / "golden-items.json"

WIKI_API = "https://peak.wiki.gg/api.php"
# 与 crawler/WikiApiClient.USER_AGENT 同一个：伪装 Googlebot 是绕过 Cloudflare 的实测通道
USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

# 与 crawler/WikiApiClient 一致：全量 134 行一次拉完，不分页
LIMIT = 500
TABLES = "Items"

# 与 WikiApiClient.WIKITEXT_TITLES_PER_REQUEST 一致（MediaWiki 对普通用户的上限）
WIKITEXT_TITLES_PER_REQUEST = 50

# Windows 文件名建不出来的字符（`?` 是实测会遇到的那个：数据源上有 File:Bugle?.png）。
# 与 freeze_baseline.py 的 UNSAFE_FILE_CHARS 同一口径：离线语料按同样的名字落盘。
UNSAFE_FILE_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')

# 与 crawler/WikiApiClient.FIELDS 一致。加字段要同时改 CargoRow.from_map
FIELDS = ",".join([
    "_pageName=page", "display", "type", "rarity", "biome", "location", "source",
    "uses", "weight", "hunger", "bonus", "heat", "cold", "coldTime", "injury",
    "poison", "poisonTime", "poisonStart", "spores", "drowsy", "curse", "thorns", "removed",
])

DB = {
    "host": os.environ.get("GUIDE_DB_HOST", "127.0.0.1"),
    "port": int(os.environ.get("GUIDE_DB_PORT", "3306")),
    "user": os.environ.get("GUIDE_DB_USER", "root"),
    "password": os.environ.get("GUIDE_DB_PASSWORD", ""),
    "database": os.environ.get("GUIDE_DB_NAME", "peak_guide"),
}

REDIS = {
    "host": os.environ.get("GUIDE_REDIS_HOST", "127.0.0.1"),
    "port": int(os.environ.get("GUIDE_REDIS_PORT", "6379")),
    "password": os.environ.get("GUIDE_REDIS_PASSWORD") or None,
    "db": int(os.environ.get("GUIDE_REDIS_DB", "0")),
}

# 与 cache/ItemCache、cache/AnswerCache、cache/QuizBankCache 的常量一致
ITEM_CACHE_KEY = "guide:item:all"
ANSWER_CACHE_PREFIX = "guide:answer:"
QUIZ_BANK_CACHE_PREFIX = "guide:quiz:bank:"


# --------------------------------------------------------------------------------------
# 数据源访问（只走 api.php；HTML 页面有 Cloudflare JS 挑战）
# --------------------------------------------------------------------------------------

def http_json(query: str) -> dict:
    request = urllib.request.Request(WIKI_API + "?" + query, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_all_items() -> list["CargoRow"]:
    """拉 Cargo 表 Items 的全量行。HTTP / 解析失败直接抛，由 main 落进报告。"""
    query = ("action=cargoquery&tables=" + TABLES
             + "&fields=" + urllib.parse.quote(FIELDS, safe="")
             + "&limit=" + str(LIMIT)
             + "&format=json")
    response = http_json(query)

    error = response.get("error")
    if error:
        raise RuntimeError("数据源返回错误:" + json.dumps(error, ensure_ascii=False))
    rows = response.get("cargoquery")
    if rows is None:
        raise RuntimeError("数据源响应里没有 cargoquery 字段")

    return [CargoRow.from_map(_unwrap_row(row)) for row in rows]


def _unwrap_row(row: dict) -> dict:
    """cargoquery 每行外面套着一层 `{"title": {字段: 取值}}`，字段全在 title 里面。"""
    title = row.get("title")
    if isinstance(title, dict):
        return {str(k): v for k, v in title.items()}
    return row


def read_items_file(path: Path) -> list["CargoRow"]:
    """从本地 cargo 快照读行（离线复跑用；快照就是 cargoquery 的原始响应）。"""
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    rows = payload.get("cargoquery")
    if rows is None:
        raise RuntimeError("本地快照里没有 cargoquery 字段:" + str(path))
    return [CargoRow.from_map(_unwrap_row(row)) for row in rows]


def fetch_wikitext(page_names: list[str]) -> dict[str, str]:
    """批量拉页面源文；每包最多 50 个标题。

    熟食覆写值、描述、成就都不在 Cargo 表里，只在页面源文里，所以采集必须再走这一趟。
    响应里的标题是数据源规范化之后的写法，按 normalized 对照还原成**请求时**的页面名。
    """
    result: dict[str, str] = {}
    for start in range(0, len(page_names), WIKITEXT_TITLES_PER_REQUEST):
        batch = page_names[start:start + WIKITEXT_TITLES_PER_REQUEST]
        query = ("action=query&format=json&formatversion=2&prop=revisions"
                 "&rvprop=content&rvslots=main&titles="
                 + urllib.parse.quote("|".join(batch), safe=""))
        response = http_json(query)
        error = response.get("error")
        if error:
            raise RuntimeError("数据源返回错误:" + json.dumps(error, ensure_ascii=False))
        query_obj = response.get("query") or {}
        normalized = {entry["from"]: entry["to"] for entry in query_obj.get("normalized") or []}
        by_title: dict[str, str] = {}
        for page in query_obj.get("pages") or []:
            revisions = page.get("revisions") or []
            if not (revisions and page.get("title")):
                continue
            main = (revisions[0].get("slots") or {}).get("main") or {}
            if "content" in main:
                by_title[page["title"]] = main["content"]
        for name in batch:
            content = by_title.get(normalized.get(name, name)) or by_title.get(name)
            if content is not None:
                result[name] = content
    return result


def read_wikitext_dir(directory: Path, page_names: list[str]) -> dict[str, str]:
    """从离线语料目录读页面源文（与 freeze_baseline.py 的落盘命名同一口径）。"""
    result: dict[str, str] = {}
    for name in page_names:
        path = Path(directory) / (UNSAFE_FILE_CHARS.sub("_", name) + ".wikitext")
        if path.exists():
            result[name] = path.read_text(encoding="utf-8")
    return result


# --------------------------------------------------------------------------------------
# 数据源行：在边界上立刻收口成强类型
# --------------------------------------------------------------------------------------

@dataclass
class CargoRow:
    page: str | None = None
    display: str | None = None
    type: str | None = None
    rarity: str | None = None
    biome: str | None = None
    location: str | None = None
    source: str | None = None
    uses: float | None = None
    weight: float | None = None
    hunger: float | None = None
    bonus: float | None = None
    heat: float | None = None
    cold: float | None = None
    cold_time: float | None = None
    injury: float | None = None
    poison: float | None = None
    poison_time: float | None = None
    poison_start: float | None = None
    spores: float | None = None
    drowsy: float | None = None
    curse: float | None = None
    thorns: float | None = None
    removed: bool = False

    @staticmethod
    def from_map(raw: dict | None) -> "CargoRow":
        m = {(k or "").lower(): v for k, v in (raw or {}).items()}
        return CargoRow(
            page=_text(m, "page"),
            display=_text(m, "display"),
            type=_multi_value(m, "type"),
            rarity=_text(m, "rarity"),
            biome=_multi_value(m, "biome"),
            location=_multi_value(m, "location"),
            source=_multi_value(m, "source"),
            uses=_number(m, "uses"),
            weight=_number(m, "weight"),
            hunger=_number(m, "hunger"),
            bonus=_number(m, "bonus"),
            heat=_number(m, "heat"),
            cold=_number(m, "cold"),
            cold_time=_number(m, "coldtime"),
            injury=_number(m, "injury"),
            poison=_number(m, "poison"),
            poison_time=_number(m, "poisontime"),
            poison_start=_number(m, "poisonstart"),
            spores=_number(m, "spores"),
            drowsy=_number(m, "drowsy"),
            curse=_number(m, "curse"),
            thorns=_number(m, "thorns"),
            removed=_bool(m, "removed"),
        )


def _text(m: dict, key: str) -> str | None:
    """空值有两种形态：字段不出现、或出现了但是空串。两者等价。"""
    v = m.get(key)
    if v is None:
        return None
    s = str(v).strip()
    return s or None


def _multi_value(m: dict, key: str) -> str | None:
    """多值字段正常是逗号分隔的字符串；某些 Cargo 版本会直接给数组，一并接住。"""
    v = m.get(key)
    if isinstance(v, list):
        return ", ".join(str(x) for x in v)
    return _text(m, key)


def _number(m: dict, key: str) -> float | None:
    """空 → None；非空但解析不出数字 → 抛，让这一条进失败明细，而不是把脏值静默存进库。"""
    v = m.get(key)
    if v is None:
        return None
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return float(v)
    s = str(v).strip()
    if not s:
        return None
    try:
        return float(s)
    except ValueError as exc:
        raise ValueError(f"字段 {key} 的取值不是数字:{s}") from exc


def _bool(m: dict, key: str) -> bool:
    v = m.get(key)
    if v is None:
        return False
    if isinstance(v, bool):
        return v
    s = str(v).strip()
    # 数据源的 Boolean 列在 JSON 里可能是 true/false，也可能是 1/0
    return s.lower() == "true" or s == "1"


# --------------------------------------------------------------------------------------
# 中文名字典（写死；与 crawler/TagDictionary、crawler/EffectDictionary 一一对应）
# --------------------------------------------------------------------------------------

TYPE_ZH = {
    "Food": "食物", "Natural food": "天然食物", "Packaged food": "包装食品",
    "Berry": "浆果", "Mushroom": "蘑菇", "Consumable": "消耗品",
    "Equipment": "装备", "Deployable": "可放置物", "Mystical item": "神秘物品",
    "Amulet": "护身符", "Misc": "杂物", "Enemy": "敌人",
}
BIOME_ZH = {
    "Tropics": "雨林", "Roots": "森蕈", "Mesa": "方山", "Shore": "海岸", "Gloom": "雾沼",
    "Alpine": "雪山", "Airport": "机场", "Peak": "顶峰", "Caldera": "火山",
    "The Citadel": "城塞", "The Kiln": "熔炉",
}
RARITY_ZH = {
    "Common": "普通", "Uncommon": "优秀", "Rare": "稀有", "Epic": "史诗",
    "Mythic": "神话", "Legendary": "传说", "Ridiculously Rare": "极其罕见",
}
SOURCE_ZH = {
    "Regular Luggage": "普通行李", "Big Luggage": "大型行李", "Ancient Luggage": "古代行李",
    "Explorer's Luggage": "探险家行李", "Ancient Statue": "古代雕像", "Ancient Statues": "古代雕像",
    "Clown Luggage": "小丑行李", "Scout Statue": "童军雕像", "Stone Scout": "石头童军",
    "Crash Site": "坠机点", "Campfires": "篝火", "Airport": "机场", "Luggage": "行李",
    "Big Egg": "大蛋", "Small Egg": "小蛋", "Blue Berrynana": "蓝莓蕉",
    "Brown Berrynana": "棕莓蕉", "Pink Berrynana": "粉莓蕉", "Yellow Berrynana": "黄莓蕉",
}
LOCATION_ZH = {
    "Crash Site": "坠机点", "Tomb": "墓穴", "Photobooth": "照相亭", "Scout Statue": "童军雕像",
    "Campfires": "篝火", "On ground": "地面", "In Luggage": "行李中",
    "Peak": "顶峰", "Stone Scout": "石头童军",
}
FLAG_ZH = {"cookable": "可烹饪", "removed": "已移除"}

DICTIONARIES = {
    "type": TYPE_ZH, "biome": BIOME_ZH, "rarity": RARITY_ZH,
    "source": SOURCE_ZH, "location": LOCATION_ZH, "flag": FLAG_ZH,
}

# location 原值里 Campfire 与 Campfires 指同一处，定稿要求归一成 Campfires
LOCATION_CODE_ALIASES = {"Campfire": "Campfires"}

TYPE = "type"
BIOME = "biome"
RARITY = "rarity"
SOURCE = "source"
LOCATION = "location"
FLAG = "flag"
FLAG_COOKABLE = "cookable"
FLAG_REMOVED = "removed"


def lookup_zh(dimension: str, value: str) -> str | None:
    return DICTIONARIES.get(dimension, {}).get(value)


def canonical_location_code(value: str) -> str:
    return LOCATION_CODE_ALIASES.get(value, value)


# --------------------------------------------------------------------------------------
# 转换：数据源行 → 图鉴条目字段
# --------------------------------------------------------------------------------------

# 模板公式：熟食饱食总量 = 生值 × 2；熟食加成总量 = 生值 × 1.5，生值没有时按模板兜底给 10
HUNGER_FACTOR = 2.0
BONUS_FACTOR = 1.5
BONUS_FALLBACK = 10.0

# location 的原值形如 [[Crash Site]] / [[Peak (biome)|Peak]]；与 crawler/WikitextUtil.LINK 同一口径
LINK = re.compile(r"\[\[\s*([^\[\]|]*)\|([^\[\]]*?)\s*\]\]|\[\[\s*([^\[\]|]*?)\s*\]\]")


def strip_link_markup(raw: str) -> str:
    """剥掉取值里的方括号链接标记；只处理 location 这类取值，页面长文本的清洗在 #23。"""
    return LINK.sub(lambda m: m.group(3) if m.group(2) is None else m.group(2), raw)


def split_multi_value(raw: str | None) -> list[str]:
    """拆逗号分隔的多值字段（拆在应用层做：SQL 里 = 匹配不到、LIKE 会误命中）。"""
    if not raw or not raw.strip():
        return []
    return [piece.strip() for piece in raw.split(",") if piece.strip()]


def convert(row: CargoRow, params: page_source.CookParams) -> tuple[dict, list[str]]:
    """一行 + 页面源文参数 → 图鉴条目字段 + 这条里出现的字典未知取值。

    @throws ValueError 该行连英文名都没有时抛出，由上层记进失败明细
    """
    # 英文名取数据源的 display：3 个毒蘑菇变体在数据源里本来就是独立行、display 与普通页名不同
    name_en = row.display
    if not name_en or not name_en.strip():
        # 没有名字就没法按 nameEn upsert：硬塞进去只会在库里留下查不到的垃圾行
        raise ValueError("数据源该行没有 display(英文名)，无法作为图鉴条目")

    name_en = name_en.strip()
    unknown: list[str] = []
    type_values = split_multi_value(row.type)

    cooked = build_cooked_effects(row, type_values, params)
    item = {
        "nameEn": name_en,
        "weight": row.weight,
        "tag": build_tags(row, type_values, cooked, unknown),
        "effect": build_raw_effects(row) + cooked,
    }
    return item, unknown


def build_tags(row: CargoRow, type_values: list[str], cooked: list[dict], unknown: list[str]) -> list[dict]:
    tags: list[dict] = []

    for value in type_values:
        _add_tag(tags, TYPE, value, unknown)
    for value in split_multi_value(row.biome):
        _add_tag(tags, BIOME, value, unknown)
    _add_tag(tags, RARITY, row.rarity, unknown)
    for value in split_multi_value(row.source):
        _add_tag(tags, SOURCE, value, unknown)
    for raw in split_multi_value(row.location):
        # 原值是 wikitext（如 [[Crash Site]]），先剥标记；Campfire 与 Campfires 合并成同一个码
        _add_tag(tags, LOCATION, canonical_location_code(strip_link_markup(raw)), unknown)
    if row.removed:
        # 已移除条目照常入库，列表不过滤 —— 图鉴收录游戏里已经看不到的东西
        _add_tag(tags, FLAG, FLAG_REMOVED, unknown)
    # flag=cookable：存在非零熟食数值。显式写的 0（"煮掉了"这类信息）不算，只写了 0 的仍不带旗标
    if contains_non_zero(cooked):
        _add_tag(tags, FLAG, FLAG_COOKABLE, unknown)

    return tags


def _add_tag(tags: list[dict], dimension: str, value: str | None, unknown: list[str]) -> None:
    if not value or not value.strip():
        return
    value = value.strip()
    name_zh = lookup_zh(dimension, value)
    if name_zh is None:
        unknown.append(f"{dimension}={value}")
    tags.append({"code": dimension, "value": value, "nameZh": name_zh})


def build_raw_effects(row: CargoRow) -> list[dict]:
    effects: list[dict] = []
    _add_effect(effects, "HUNGER", row.hunger)
    _add_effect(effects, "BONUS", row.bonus)
    _add_effect(effects, "HEAT", row.heat)
    _add_effect(effects, "COLD", row.cold, row.cold_time)
    _add_effect(effects, "INJURY", row.injury)
    _add_effect(effects, "POISON", row.poison, row.poison_time, row.poison_start)
    _add_effect(effects, "SPORES", row.spores)
    _add_effect(effects, "DROWSY", row.drowsy)
    _add_effect(effects, "CURSE", row.curse)
    _add_effect(effects, "THORNS", row.thorns)
    # USES 是使用次数；uses=0 表示没有使用次数，不写
    if row.uses is not None and row.uses != 0:
        _add_effect(effects, "USES", row.uses)
    return effects


def build_cooked_effects(row: CargoRow, type_values: list[str],
                         params: page_source.CookParams) -> list[dict]:
    """熟食值：页面源文覆写值优先，否则按模板公式算（票 #23 起读页面源文）。"""
    if not _has_type(type_values, "Food"):
        return []

    berry = _has_type(type_values, "Berry")
    cooked: list[dict] = []

    # HasCookingBonus = no/breaks 的条目（含带 Food 标签的 Fortified Milk）不生成这两个值
    if not params.suppresses_cooking_bonus():
        _add_derived(cooked, "HUNGER_COOKED", params.hunger_cooked, _scaled(row.hunger, HUNGER_FACTOR))
        _add_derived(cooked, "BONUS_COOKED", params.bonus_cooked, _cooked_bonus(row.bonus))

    if berry:
        # 浆果煮熟毒/刺清零（只有生值存在时才写：本来就没有的东西，"煮熟后为 0"是句空话）
        _add_explicit_zero(cooked, "POISON_COOKED", row.poison)
        _add_explicit_zero(cooked, "THORNS_COOKED", row.thorns)
    else:
        # 其余条目毒/刺/孢子原样保留（含附属值）—— 毒蘑菇的毒煮不掉
        _add_non_zero(cooked, "POISON_COOKED", row.poison, row.poison_time, row.poison_start)
        _add_non_zero(cooked, "THORNS_COOKED", row.thorns)
        _add_non_zero(cooked, "SPORES_COOKED", row.spores)

    # Shroomberry = 浆果 + 蘑菇：煮熟后孢子归零
    if berry and _has_type(type_values, "Mushroom"):
        _add_explicit_zero(cooked, "SPORES_COOKED", row.spores)

    return cooked


def contains_non_zero(cooked: list[dict]) -> bool:
    """可烹饪判据：熟食效果里至少有一个非零数值。显式写的 0 不算。"""
    return any(effect["value"] not in (None, 0) for effect in cooked)


def _add_effect(effects: list[dict], code: str, value, duration=None, start_delay=None) -> None:
    """值为空则整条效果都不进数组（"空字段不进数组"）。"""
    if value is None:
        return
    effects.append({"code": code, "value": value, "duration": duration, "startDelay": start_delay})


def _add_non_zero(effects: list[dict], code: str, value, duration=None, start_delay=None) -> None:
    """算出 0 的不写入（0 没有可对比的信息）；显式写 0 的例外走 _add_explicit_zero。"""
    if value is None or value == 0:
        return
    _add_effect(effects, code, value, duration, start_delay)


def _add_derived(effects: list[dict], code: str, override, derived) -> None:
    """覆写值优先，否则用公式算出来的值；两边都没有 → 不生成。"""
    _add_non_zero(effects, code, override if override is not None else derived)


def _add_explicit_zero(effects: list[dict], code: str, raw_value) -> None:
    """显式写 0：生值存在时才写（没有的东西谈不上"清零"）。"""
    if raw_value is None:
        return
    _add_effect(effects, code, 0.0)


def _scaled(raw, factor: float):
    return None if raw is None else raw * factor


def _cooked_bonus(raw_bonus):
    return BONUS_FALLBACK if raw_bonus is None else raw_bonus * BONUS_FACTOR


def _has_type(type_values: list[str], token: str) -> bool:
    return any(token.lower() == value.lower() for value in type_values)


# --------------------------------------------------------------------------------------
# 落库：按 nameEn upsert，**只写本票负责的三列**，中文名与描述原值保留
# --------------------------------------------------------------------------------------

def persist(items: list[dict], failures: list[dict]) -> int:
    """逐条 upsert；单条失败只跳过那一条并记进 failures，不影响整批。

    表上没有 name_en 唯一索引（唯一真相是 pojo/Item.java，仓库里没有 DDL），
    所以照 Java 版的做法：先按 name_en 查 id，命中就更新、否则插入。

    只写 weight / tag / effect / description / achievement 五列：name_zh、description_zh、
    icon 是「对照表回填」与 #24 的地盘，一个字都不动 —— 这既是"已有中文名保留"的实现，
    也是"不碰中文名与图标"的落实。
    """
    import pymysql

    conn = pymysql.connect(**DB, charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
    success = 0
    try:
        for item in items:
            try:
                with conn.cursor() as cur:
                    cur.execute("SELECT id FROM item WHERE name_en = %s LIMIT 1", (item["nameEn"],))
                    existing = cur.fetchone()
                    tag_json = json.dumps(item["tag"], ensure_ascii=False)
                    effect_json = json.dumps(item["effect"], ensure_ascii=False)
                    if existing:
                        cur.execute(
                            "UPDATE item SET weight = %s, tag = %s, effect = %s,"
                            " description = %s, achievement = %s WHERE id = %s",
                            (item["weight"], tag_json, effect_json,
                             item["description"], item["achievement"], existing["id"]))
                    else:
                        cur.execute(
                            "INSERT INTO item (name_en, weight, tag, effect, description, achievement)"
                            " VALUES (%s, %s, %s, %s, %s, %s)",
                            (item["nameEn"], item["weight"], tag_json, effect_json,
                             item["description"], item["achievement"]))
                # 逐条提交：一次 rollback 只该退掉失败的那一条，而不是把先前成功的行一起退掉
                conn.commit()
                success += 1
            except Exception as exc:  # 单条失败不中断整批
                conn.rollback()
                failures.append({"page": None, "nameEn": item.get("nameEn"), "reason": _failure_reason(exc)})
    finally:
        conn.close()
    return success


def _failure_reason(exc: Exception) -> str:
    message = str(exc).strip()
    return message or type(exc).__name__


# --------------------------------------------------------------------------------------
# 缓存失效：只用得上 AUTH/SELECT/DEL/KEYS，用 stdlib socket 发 RESP，不引 redis-py
# --------------------------------------------------------------------------------------

class RedisClient:
    """够用的 Redis 客户端。不复用连接池、不做重试 —— 采集是一次性离线动作。"""

    def __init__(self, host: str, port: int, password: str | None = None, db: int = 0, timeout: float = 3.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.reader = self.sock.makefile("rb")
        if password:
            self.send("AUTH", password)
        if db:
            self.send("SELECT", str(db))

    def send(self, *args):
        payload = [f"*{len(args)}\r\n".encode()]
        for arg in args:
            raw = arg.encode("utf-8") if isinstance(arg, str) else arg
            payload.append(f"${len(raw)}\r\n".encode() + raw + b"\r\n")
        self.sock.sendall(b"".join(payload))
        return self._read()

    def _read(self):
        line = self.reader.readline()
        if not line:
            raise ConnectionError("Redis 连接被关闭")
        kind, body = line[:1], line[1:-2]
        if kind == b"+":
            return body.decode()
        if kind == b"-":
            raise RuntimeError("Redis 错误:" + body.decode())
        if kind == b":":
            return int(body)
        if kind == b"$":
            length = int(body)
            if length == -1:
                return None
            return self.reader.read(length + 2)[:-2].decode("utf-8", "replace")
        if kind == b"*":
            count = int(body)
            return None if count == -1 else [self._read() for _ in range(count)]
        raise RuntimeError("无法解析的 Redis 响应:" + repr(line))

    def delete(self, *keys) -> int:
        return self.send("DEL", *keys)

    def keys(self, pattern: str) -> list[str]:
        return self.send("KEYS", pattern) or []

    def close(self) -> None:
        try:
            self.reader.close()
            self.sock.close()
        except OSError:
            pass


def evict_caches() -> list[str]:
    """删掉采集影响的三个缓存 key；Redis 连不上只记警告，不把一次成功的采集变成失败。"""
    try:
        redis = RedisClient(**REDIS)
    except Exception as exc:
        return [f"Redis 连不上（{_failure_reason(exc)}），缓存未失效，接口可能仍返回旧数据"]

    warnings: list[str] = []
    try:
        redis.delete(ITEM_CACHE_KEY)
        for prefix in (ANSWER_CACHE_PREFIX, QUIZ_BANK_CACHE_PREFIX):
            found = redis.keys(prefix + "*")
            if found:
                redis.delete(*found)
    except Exception as exc:
        warnings.append(f"缓存失效失败（{_failure_reason(exc)}），接口可能仍返回旧数据")
    finally:
        redis.close()
    return warnings


# --------------------------------------------------------------------------------------
# 金标准比对（--check）
# --------------------------------------------------------------------------------------

def golden_count(golden_path: Path) -> int | None:
    """金标准里的条目数；快照读不到时返回 None（行数护栏是提示，不该挡住落库）。"""
    try:
        return json.loads(golden_path.read_text(encoding="utf-8"))["count"]
    except (OSError, KeyError, TypeError, ValueError):
        return None


def compare_with_golden(items: list[dict], golden_path: Path) -> dict:
    """逐条比对 tag / effect / weight / description / achievement 五列；差异全部列出。"""
    golden = json.loads(golden_path.read_text(encoding="utf-8"))
    by_name = {item["nameEn"]: item for item in golden["items"]}

    diffs = []
    for item in items:
        name = item["nameEn"]
        expected = by_name.get(name)
        if expected is None:
            diffs.append({"nameEn": name, "kind": "金标准里没有这条",
                          "reason": "数据源新增条目，待重跑 --step1 更新金标准"})
            continue
        for fieldname in ("tag", "effect", "weight", "description", "achievement"):
            if not _same(item[fieldname], expected[fieldname]):
                diffs.append({
                    "nameEn": name,
                    "kind": fieldname,
                    "actual": item[fieldname],
                    "expected": expected[fieldname],
                    "reason": "未归类差异，需查证",
                })

    missing = sorted(set(by_name) - {item["nameEn"] for item in items})
    for name in missing:
        diffs.append({"nameEn": name, "kind": "数据源里没有这条",
                      "reason": "条目被数据源删除，本票只 upsert 不反删"})

    return {
        "golden": str(golden_path),
        "goldenCount": golden["count"],
        "convertedCount": len(items),
        "diffCount": len(diffs),
        "diffs": diffs,
    }


def _same(actual, expected) -> bool:
    """数值列用 6 位小数比较：库里是 float(32 位)，公式在 Python 里是 double。"""
    if isinstance(actual, float) and isinstance(expected, float):
        return round(actual, 6) == round(expected, 6)
    return actual == expected


# --------------------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------------------

def crawl(rows: list["CargoRow"], wikitext_by_page: dict[str, str]) -> tuple[list[dict], list[str], list[dict]]:
    """转换：结构化行 + 页面源文 → 图鉴条目。

    @return (条目列表, 警告, 单条失败明细)
    """
    warnings: list[str] = []
    if not rows:
        warnings.append("数据源返回 0 行，请确认接口与字段清单是否仍然有效")

    items: list[dict] = []
    failures: list[dict] = []
    unknown_counter: dict[str, int] = {}
    for row in rows:
        # 源文没抓到、或页面上没有 Infobox 时走 EMPTY：熟食值按公式算，描述留空
        wikitext = wikitext_by_page.get(row.page)
        try:
            params = page_source.read_params(wikitext, row.display) if wikitext else page_source.EMPTY_PARAMS
            item, unknown = convert(row, params)
            # 描述与成就同样出自页面源文：取不到就留空，绝不因为一段散文把这条挡在库外
            description, achievement = (
                page_source.read_page_text(wikitext, row.display) if wikitext else (None, None))
            item["description"] = description
            item["achievement"] = achievement
            items.append(item)
            for value in unknown:
                unknown_counter[value] = unknown_counter.get(value, 0) + 1
        except Exception as exc:
            failures.append({"page": row.page, "nameEn": row.display, "reason": _failure_reason(exc)})

    for value, count in unknown_counter.items():
        warnings.append(f"字典未知取值 {value}(出现 {count} 条)，已保留原值、中文名留空")
    return items, warnings, failures


def load_wikitext(args, page_names: list[str]) -> tuple[dict[str, str], list[str]]:
    """取页面源文：`--wikitext-dir` 走离线语料，否则联网批量拉。

    整体失败只记警告、不中断采集：源文少了只会让熟食值退回公式、描述留空，
    而结构化那半边是独立的、丢不起（与 Java 版 fetchWikitext 同一取舍）。
    """
    if args.wikitext_dir:
        return read_wikitext_dir(args.wikitext_dir, page_names), []
    try:
        wikitext_by_page = fetch_wikitext(page_names)
    except Exception as exc:
        return {}, [f"页面源文没取到（{_failure_reason(exc)}），"
                    f"本次采集的熟食覆写值与描述全部按缺省处理"]
    if len(wikitext_by_page) < len(page_names):
        return wikitext_by_page, [
            f"有 {len(page_names) - len(wikitext_by_page)} 个页面没取到源文，"
            f"这些条目的熟食值按公式算、描述留空"]
    return wikitext_by_page, []


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description="图鉴采集：拉取 + 页面源文 + 落库闭环（#22/#23）")
    parser.add_argument("--check", action="store_true", help="只转换并与金标准比对，不写库")
    parser.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN, help="金标准快照路径")
    parser.add_argument("--items-file", type=Path, help="从本地 cargo 快照读结构化行（离线复跑）")
    parser.add_argument("--wikitext-dir", type=Path, help="从本地语料目录读页面源文（离线复跑）")
    parser.add_argument("--report", type=Path, help="把报告写到这个 JSON 文件")
    args = parser.parse_args()

    try:
        rows = read_items_file(args.items_file) if args.items_file else fetch_all_items()
        page_names = list(dict.fromkeys(row.page for row in rows if row.page and row.page.strip()))
        wikitext_by_page, wikitext_warnings = load_wikitext(args, page_names)
        items, warnings, failures = crawl(rows, wikitext_by_page)
    except Exception as exc:
        report = {"fetchError": _failure_reason(exc), "fetchedRows": 0, "warnings": [],
                  "failures": [], "successCount": 0}
        print(json.dumps(report, ensure_ascii=False, indent=1))
        if args.report:
            _write(args.report, report)
        return 1

    warnings = wikitext_warnings + warnings

    print(f"拉到 {len(items) + len(failures)} 行，转换成功 {len(items)} 条，失败 {len(failures)} 条")
    for warning in warnings:
        print("警告：" + warning)
    for failure in failures:
        print(f"失败：page={failure['page']} display={failure['nameEn']} 原因={failure['reason']}")

    if args.check:
        result = compare_with_golden(items, args.golden)
        result["warnings"] = warnings
        result["failures"] = failures
        print(f"\n与金标准比对：金标准 {result['goldenCount']} 条，"
              f"本次转换 {result['convertedCount']} 条，差异 {result['diffCount']} 处")
        for diff in result["diffs"]:
            print(f"- {diff['nameEn']} [{diff['kind']}] 实际={diff.get('actual')} 期望={diff.get('expected')}")
            print(f"    理由：{diff['reason']}")
        if args.report:
            _write(args.report, result)
        return 1 if result["diffCount"] else 0

    expected = golden_count(args.golden)
    if expected is not None and len(items) + len(failures) != expected:
        warnings.append(f"数据源拉到 {len(items) + len(failures)} 行，与金标准 {expected} 条不一致，"
                        f"数据源可能变了，请重跑 --check 核对")

    success = persist(items, failures)
    evict_warnings = evict_caches()
    warnings.extend(evict_warnings)
    report = {"fetchedRows": len(items) + len(failures), "fetchError": None,
              "successCount": success, "failures": failures, "warnings": warnings}
    print(f"\n落库成功 {success} 条（写 weight / tag / effect / description / achievement 五列，"
          f"中文名、中文描述、图标原值保留）")
    for warning in evict_warnings:
        print("警告：" + warning)
    if args.report:
        _write(args.report, report)
    return 0 if not failures else 1


def _write(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n",
                    encoding="utf-8", newline="\n")
    print(f"报告已写入 {path}")


if __name__ == "__main__":
    sys.exit(main())
