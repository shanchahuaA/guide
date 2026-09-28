# -*- coding: utf-8 -*-
r"""
从 PEAK 游戏本体提取官方词条表（docs/local/peaks_terms.CSV，不进仓库），
并把官方简体译名回填到「名称翻译对照表」的 name_zh 列。

游戏本体的中文只存在于一个文件里：<PEAK>\PEAK_Data\resources.assets
（整个安装目录扫过，其余文件无 CJK 文本）。该文件里 SerializedTermsData 是一段
JSON：843 个词条 × 15 种语言，简体中文在下标 9。本脚本只取「键 / 英文 / 简体中文」三列，
其余 13 种语言丢弃。

回填规则（docs/local/name-translation.CSV）：
- 只填空的 name_zh，不覆盖已有值（保护用户手改过的格）。
- 只认游戏里 NAME_ 开头的词条，避免 UI 文案误撞物品名。
- 英文名先做归一化（NFKC、弯引号转直引号、空白折叠）再匹配，以便吃下
  SCOUT’S HONOR 这类弯引号差异。

只读本地文件，不发任何网络请求。
"""
import csv
import io
import json
import os
import unicodedata

PEAK_DIR = os.environ.get("PEAK_DIR", r"D:\SteamLibrary\steamapps\common\PEAK")
ASSETS = os.path.join(PEAK_DIR, "PEAK_Data", "resources.assets")
VERSION_FILE = os.path.join(PEAK_DIR, "version.txt")

# 词条表在 resources.assets 里的起点标记。偏移量随游戏版本变化，所以按标记找，不写死。
MARKER = b'{"CURRENT_LANGUAGE"'
ZH_NAME = "简体中文"

TERMS_OUT = r"docs/local/peaks_terms.CSV"
TABLE = r"docs/local/name-translation.CSV"
MAP_OUT = r"docs/local/name-zh-map.CSV"

PROVENANCE_PREFIX = "# name_zh 由 gen_peaks_terms.py 从 PEAK 游戏本体官方简体译名回填"


# 人工判定表：数据源按维度切得比游戏细，游戏只给一个官方名，这些行靠判定补。
# 键是归一化后的数据源 en（norm() 的结果）；值是官方中文，None 表示判定为「不填」。
MANUAL = {
    # 四色莓蕉皮：游戏只有一条 Berrynana Peel（莓蕉皮），颜色前缀按游戏自己的用字拼。
    # 游戏把 BROWN BERRYNANA 译作「棕莓蕉」，所以这里用「棕」而不是「褐」。
    "BLUE BERRYNANA PEEL": "蓝莓蕉皮",
    "BROWN BERRYNANA PEEL": "棕莓蕉皮",
    "PINK BERRYNANA PEEL": "粉莓蕉皮",
    "YELLOW BERRYNANA PEEL": "黄莓蕉皮",
    # 毒蘑菇与无毒蘑菇在游戏里共名，官方两边都给「喇叭菇」这样的名字，不区分。
    # 但图鉴列表页会出现两条完全同名的条目、用户分不出哪条有毒，所以产品决定
    # 在中文名后加「（有毒）」区分（2026-09-27，用户拍板）。
    "BUGLE SHROOM (POISONOUS)": "喇叭菇（有毒）",
    "BUTTON SHROOM (POISONOUS)": "馒头菇（有毒）",
    "CLUSTER SHROOM (POISONOUS)": "银针菇（有毒）",
    # 游戏词条键是 Coconut Half。
    "HALF-COCONUT": "半边椰子",
    # Bugle?：数据源标记 removed=1 的被删物品。产品决定是图鉴照常收录游戏里已经看不到
    # 的东西，条目在 flag 维度带 removed 旗标（采集写进 tag，接口与详情都据此渲染），所以名字照填。
    # 游戏把 BUGLE 译作「喇叭」，这里按用户定稿用「号角」。
    "BUGLE?": "号角",
    # Cooked Bird：游戏没有独立词条，熟度靠 COOKED_* 前缀模板拼
    # （COOKED_COOKED = "#烧熟了"，# 是条目名占位符）。代入鸟的官方原名
    # （NAME_BIRD = "“鸟”"，引号是游戏自己名里的），拼出带引号的熟名。
    "COOKED BIRD": "“鸟”烧熟了",
}

def read_game_version():
    """version.txt 是两行：版本号 + 构建号，用空格接起来当出处。"""
    try:
        with io.open(VERSION_FILE, encoding="utf-8") as f:
            parts = [l.strip() for l in f.read().splitlines() if l.strip()]
    except OSError:
        return "未知"
    return " ".join(parts) if parts else "未知"


def load_terms():
    """返回 (词条列表, 语言列表)。词条形如 (key, 英文, 简体中文)。"""
    with open(ASSETS, "rb") as f:
        data = f.read()

    start = data.find(MARKER)
    if start < 0:
        raise SystemExit("%s 里找不到词条表标记 %r，游戏版本大概变了" % (ASSETS, MARKER))

    # 词条表比 1MB 大，逐步扩窗解码，直到 raw_decode 吃掉整个 JSON。
    window = 1 << 20
    blob = None
    while window <= len(data) - start:
        text = data[start:start + window].decode("utf-8", errors="replace")
        try:
            blob, _ = json.JSONDecoder().raw_decode(text)
            break
        except ValueError:
            window *= 2
    if blob is None:
        raise SystemExit("%s 的词条表 JSON 解析失败（从偏移 %d 起）" % (ASSETS, start))

    langs = blob["CURRENT_LANGUAGE"]
    if ZH_NAME not in langs:
        raise SystemExit("词条表里没有 %s，实际语言列表：%s" % (ZH_NAME, langs))
    zh = langs.index(ZH_NAME)

    terms = []
    for key, values in blob.items():
        if not values or values[0] is None:
            continue
        terms.append((key, str(values[0]),
                      "" if zh >= len(values) or values[zh] is None else str(values[zh])))
    return terms, langs


def norm(name):
    name = unicodedata.normalize("NFKC", str(name))
    for curly in ("\u2018", "\u2019", "\u02bc"):
        name = name.replace(curly, "'")
    return " ".join(name.split()).upper()


def write_terms(terms, langs, version):
    lines = [
        "# 工作文件，不提交（见 .gitignore）。PEAK 游戏本体官方词条表，不进仓库。",
        "# 来源：%s → SerializedTermsData（官方共 %d 种语言，本表只留英文与简体中文）"
        % (ASSETS, len([l for l in langs if l])),
        "# 游戏版本：%s。偏移量随版本变化，本表由 gen_peaks_terms.py 按标记重新定位生成。",
        "# 表头三列：key（游戏词条键）, en（英文原值）, zh（官方简体译名）。",
    ]
    lines[2] = lines[2] % version

    body = io.StringIO()
    writer = csv.writer(body, lineterminator="\r\n")
    writer.writerow(["key", "en", "zh"])
    for key, en, zh in terms:
        writer.writerow([key, en, zh])

    with io.open(TERMS_OUT, "w", encoding="utf-8-sig", newline="") as f:
        f.write("\n".join(lines) + "\n")
        f.write(body.getvalue())


def read_table():
    with io.open(TABLE, encoding="utf-8-sig", newline="") as f:
        raw = f.read()
    physical = raw.splitlines()
    comments = [l for l in physical if l.startswith("#")]
    body = "\r\n".join(l for l in physical if not l.startswith("#"))
    return comments, list(csv.reader(io.StringIO(body)))


def write_table(comments, rows):
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\r\n")
    for row in rows:
        writer.writerow(row)
    with io.open(TABLE, "w", encoding="utf-8-sig", newline="") as f:
        f.write("\n".join(comments) + "\n")
        f.write(buf.getvalue())


def fill_table(terms, version):
    """返回 (官方命中数, 人工判定数, 跳过数, 待判定列表)。"""
    official = {}
    for key, en, zh in terms:
        if key.startswith("NAME_") and en and zh:
            official.setdefault(norm(en), zh)

    comments, rows = read_table()
    header, body = rows[0], rows[1:]
    col = {name: i for i, name in enumerate(header)}
    for need in ("kind", "en", "name_zh"):
        if need not in col:
            raise SystemExit("%s 的表头里没有 %s 列，实际表头：%s" % (TABLE, need, header))

    by_official = by_manual = skipped = 0
    undecided = []
    for row in body:
        if row[col["kind"]] != "item":
            continue
        en = row[col["en"]]
        if row[col["name_zh"]].strip():
            skipped += 1
            continue
        key = norm(en)
        if key in MANUAL:
            if MANUAL[key] is None:
                undecided.append(en)
                continue
            row[col["name_zh"]] = MANUAL[key]
            by_manual += 1
            continue
        zh = official.get(key)
        if zh:
            row[col["name_zh"]] = zh
            by_official += 1
        else:
            undecided.append(en)

    comments = [c for c in comments if not c.startswith(PROVENANCE_PREFIX)]
    stamp = "%s（游戏版本 %s，官方词条命中 %d 条，人工判定 %d 条，待判定 %d 条）" % (
        PROVENANCE_PREFIX, version, by_official, by_manual, len(undecided))
    comments.append(stamp)
    write_table(comments, rows)
    return by_official, by_manual, skipped, undecided


def write_map():
    """把对照表压成 en → 中文 两列，供采集侧回填 item.name_zh。"""
    _, rows = read_table()
    header, body = rows[0], rows[1:]
    col = {n: i for i, n in enumerate(header)}
    for need in ("kind", "en", "name_zh"):
        if need not in col:
            raise SystemExit("%s 的表头里没有 %s 列，实际表头：%s" % (TABLE, need, header))

    pairs = []
    for row in body:
        if row[col["kind"]] != "item":
            continue
        zh = row[col["name_zh"]].strip()
        if zh:
            pairs.append((row[col["en"]], zh))

    lines = [
        "# 工作文件，不提交（见 .gitignore）。en → 中文 的扁平对照表，供采集侧回填 item.name_zh。",
        "# 来源：docs/local/name-translation.CSV 的 name_zh 列。",
        "# 由 gen_peaks_terms.py 生成，重跑会覆盖；改译名请改对照表，不要改本文件。",
        "# 表头两列：en（数据源 display，对回 item.name_en）, zh（要写入 item.name_zh）。",
    ]
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\r\n")
    writer.writerow(["en", "zh"])
    for en, zh in pairs:
        writer.writerow([en, zh])
    with io.open(MAP_OUT, "w", encoding="utf-8-sig", newline="") as f:
        f.write("\n".join(lines) + "\n")
        f.write(buf.getvalue())
    return len(pairs)


def main():
    version = read_game_version()
    terms, langs = load_terms()
    write_terms(terms, langs, version)
    counts = len([l for l in langs if l])
    print("词条表：%d 条 × 官方 %d 种语言（列表末位是 null 占位；本表只留英文与简体中文）"
          % (len(terms), counts))
    print("已写出 %s" % TERMS_OUT)

    by_official, by_manual, skipped, undecided = fill_table(terms, version)
    print("回填 %s：官方词条 %d 条，人工判定 %d 条，跳过（已有值）%d 条，待判定 %d 条"
          % (TABLE, by_official, by_manual, skipped, len(undecided)))
    if undecided:
        print("待判定（脚本按判定表留空，等口径确定）：")
        for name in undecided:
            print("  " + name)

    print("已写出 %s：%d 条 en → 中文" % (MAP_OUT, write_map()))


if __name__ == "__main__":
    main()
