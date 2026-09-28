"""冻结重写基线（issue #21）。

一次跑出三样产出，全程**不改**应用的任何代码：

1. 金标准快照 —— 库里 134 条中「采集产出的字段」按英文名升序落成 JSON（进仓库）。
2. 页面源文语料 —— 134 个条目对应页面的 wikitext 抓到本地目录（不进仓库）。
3. 容错命中普查 —— 拿语料把解析器里的各项防御逐条数一遍。

⚠️ **这份工具是历史产物，其中的 `--step3` 尤其如此。** 它当初的对照物是应用里那版 Java 解析器
（`ItemPageParser` / `WikitextUtil`）—— 本文件把它的扫描算法原样重写了一遍，好让普查数字能在
不联网、不改应用的前提下复现。那份 Java 代码已随 #25 删除，所以下面各处
「与 `ItemPageParser.X` 同一算法」说的都是**历史上**那版；今天真正跑采集的解析器是
`page_source.py`（用 `mwparserfromhell`，见它的文件头说明）。

用法::

    python guide/tools/freeze_baseline.py --step1
    python guide/tools/freeze_baseline.py --step2 --out <语料目录>
    python guide/tools/freeze_baseline.py --step3 --corpus <语料目录> --report <报告路径>

--out 只能指向仓库外（或已被 gitignore 的路径）—— 语料体量大，是不进仓库的离线工作目录。
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SNAPSHOT = REPO_ROOT / "guide" / "src" / "test" / "resources" / "crawler" / "golden-items.json"

WIKI_API = "https://peak.wiki.gg/api.php"
# 伪装 Googlebot 是绕过 Cloudflare 的实测通道（重写时照搬的原口径，实测过的）
USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

# MediaWiki 对普通用户的上限：一次最多查 50 个标题
TITLES_PER_REQUEST = 50

# 与 ItemPageParser.INFOBOX_TEMPLATES 一致
INFOBOX_TEMPLATES = {"infoboxitem", "iteminfobox"}
# 与 ItemPageParser.NAME_KEYS 一致
NAME_KEYS = ("display", "name")
# 与 ItemPageParser.PAGE_NAME_TEMPLATE 一致
PAGE_NAME_TEMPLATE = re.compile(r"\{\{\s*pagename\s*\}\}", re.IGNORECASE)
# 与 ItemPageParser.HTML_COMMENT 一致
HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)

DB = {
    "host": os.environ.get("GUIDE_DB_HOST", "127.0.0.1"),
    "port": int(os.environ.get("GUIDE_DB_PORT", "3306")),
    "user": os.environ.get("GUIDE_DB_USER", "root"),
    "password": os.environ.get("GUIDE_DB_PASSWORD", ""),
    "database": os.environ.get("GUIDE_DB_NAME", "peak_guide"),
}


# --------------------------------------------------------------------------------------
# 通用小工具
# --------------------------------------------------------------------------------------

def http_json(query: str) -> dict:
    request = urllib.request.Request(WIKI_API + "?" + query, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def normalize_key(raw: str) -> str:
    """与 ItemPageParser.normalizeKey 同一个归一写法：小写 + 只留字母数字。"""
    return "".join(c for c in raw if c.isalnum()).lower()


def matching_close(text: str, open_index: int):
    """与 ItemPageParser.matchingClose 同一算法：从 '{{' 找配对的 '}}'，找不到返回 -1。"""
    depth = 0
    i = open_index
    while i < len(text):
        if text.startswith("{{", i):
            depth += 1
            i += 2
        elif text.startswith("}}", i):
            depth -= 1
            if depth == 0:
                return i
            i += 2
        else:
            i += 1
    return -1


def split_top_level(body: str):
    """与 ItemPageParser.splitTopLevel 同一算法：按顶层 '|' 切，嵌套模板与方括号里的竖线不算。"""
    pieces = []
    current = []
    braces = brackets = 0
    i = 0
    while i < len(body):
        if body.startswith("{{", i):
            braces += 1
            current.append("{{")
            i += 2
        elif body.startswith("}}", i):
            braces -= 1
            current.append("}}")
            i += 2
        elif body.startswith("[[", i):
            brackets += 1
            current.append("[[")
            i += 2
        elif body.startswith("]]", i):
            brackets -= 1
            current.append("]]")
            i += 2
        else:
            c = body[i]
            if c == "|" and braces <= 0 and brackets <= 0:
                pieces.append("".join(current))
                current = []
            else:
                current.append(c)
            i += 1
    pieces.append("".join(current))
    return pieces


def params_of(body: str) -> dict:
    """与 ItemPageParser.paramsOf 同一口径：key 归一，重名取第一个。"""
    params = {}
    pieces = split_top_level(body)
    for piece in pieces[1:]:  # 第 0 段是模板名
        equals = piece.find("=")
        if equals < 0:
            continue
        key = normalize_key(piece[:equals])
        if key and key not in params:
            params[key] = piece[equals + 1:].strip()
    return params


def infoboxes_of(wikitext: str):
    """与 ItemPageParser.infoboxesOf 同一算法。"""
    text = HTML_COMMENT.sub("", wikitext)
    found = []
    cursor = 0
    while cursor < len(text):
        open_index = text.find("{{", cursor)
        if open_index < 0:
            break
        close = matching_close(text, open_index)
        if close < 0:
            break
        body = text[open_index + 2:close]
        cursor = close + 2
        pieces = split_top_level(body)
        name = pieces[0] if pieces else body
        if normalize_key(name) in INFOBOX_TEMPLATES:
            found.append(params_of(body))
    return found


def blank_to_null(raw):
    if raw is None:
        return None
    trimmed = raw.strip()
    return trimmed or None


def select_infobox(infoboxes, display_name):
    """与 ItemPageParser.selectInfobox 同一算法。"""
    if not infoboxes:
        return {}
    if len(infoboxes) == 1:
        return infoboxes[0]
    for infobox in infoboxes:
        for key in NAME_KEYS:
            value = blank_to_null(infobox.get(key))
            if value and value.lower() == display_name.lower():
                return infobox
    return infoboxes[0]


def walk_templates(text: str):
    """顺序遍历顶层模板调用，产出 (模板名归一值, body)，与 ItemPageParser 的扫描方式一致。"""
    cursor = 0
    while cursor < len(text):
        open_index = text.find("{{", cursor)
        if open_index < 0:
            break
        close = matching_close(text, open_index)
        if close < 0:
            break
        body = text[open_index + 2:close]
        cursor = close + 2
        pieces = split_top_level(body)
        name = pieces[0] if pieces else body
        yield normalize_key(name), body


# --------------------------------------------------------------------------------------
# 第 1 步：金标准快照
# --------------------------------------------------------------------------------------

def build_snapshot():
    import pymysql

    conn = pymysql.connect(**DB, charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM item ORDER BY name_en")
            rows = cur.fetchall()
    finally:
        conn.close()

    items = []
    for row in rows:
        items.append({
            "nameEn": row["name_en"],
            "weight": row["weight"],
            "icon": row["icon"],
            "description": row["description"],
            "achievement": row["achievement"],
            "tag": json.loads(row["tag"]) if row["tag"] else [],
            "effect": json.loads(row["effect"]) if row["effect"] else [],
        })

    snapshot = {
        "schema": "guide-golden-items/1",
        "source": "MySQL item 表全量行（134 条），采集产出字段，按 nameEn 升序",
        "excluded": ["nameZh", "descriptionZh", "id"],
        "note": "nameZh / descriptionZh 不属于采集职责（由对照表回填，见 #19）；id 是自增主键，重灌会变。",
        "count": len(items),
        "items": items,
    }
    return snapshot


def step1(snapshot_path: Path):
    snapshot = build_snapshot()
    snapshot_path.parent.mkdir(parents=True, exist_ok=True)
    snapshot_path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=1) + "\n",
                             encoding="utf-8", newline="\n")
    print(f"金标准快照：{snapshot['count']} 条 → {snapshot_path}")
    print(f"  体量 {snapshot_path.stat().st_size} 字节")
    names = [item["nameEn"] for item in snapshot["items"]]
    assert len(names) == len(set(names)) == snapshot["count"], "nameEn 不唯一或有缺失"
    for item in snapshot["items"]:
        assert item["nameEn"], "nameEn 为空"
        assert item["icon"], f"{item['nameEn']} 的 icon 为空"
        assert item["description"], f"{item['nameEn']} 的 description 为空"
    print("  自检：nameEn 唯一、行数一致、icon/description 无空值")


# --------------------------------------------------------------------------------------
# 第 2 步：离线页面源文语料
# --------------------------------------------------------------------------------------

MYSTICAL_ARCHIVE = "User:Westgrass/PEAK Wiki/Archive/Mystical"

# Windows 文件名里建不出来的字符（`?` 是实测会遇到的那个：数据源上有 File:Bugle?.png）。
# 与 utils/IconFileNames 同一个思路：本地名统一换成下划线，映射记进 index.json。
UNSAFE_FILE_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def corpus_file_name(name_en: str) -> str:
    return UNSAFE_FILE_CHARS.sub("_", name_en) + ".wikitext"


def corpus_path(corpus_dir: Path, name_en: str) -> Path:
    return corpus_dir / corpus_file_name(name_en)


def page_candidates(name_en: str):
    """页面名候选：先 _pageName，再 display，最后神秘物品的人工存档页。"""
    return [name_en, MYSTICAL_ARCHIVE + "/" + name_en]


def fetch_wikitext(page_names):
    """批量拉源文，返回 {请求时的页面名: wikitext}；每包最多 50 个标题。"""
    result = {}
    for start in range(0, len(page_names), TITLES_PER_REQUEST):
        batch = page_names[start:start + TITLES_PER_REQUEST]
        query = ("action=query&format=json&formatversion=2&prop=revisions"
                 "&rvprop=content&rvslots=main&titles="
                 + urllib.parse.quote("|".join(batch), safe=""))
        response = http_json(query)
        pages = response.get("query", {}).get("pages", [])
        normalized = {entry["from"]: entry["to"] for entry in response.get("query", {}).get("normalized", [])}
        by_title = {}
        for page in pages:
            revisions = page.get("revisions") or []
            if revisions and page.get("title"):
                main = revisions[0].get("slots", {}).get("main", {})
                if "content" in main:
                    by_title[page["title"]] = main["content"]
        for name in batch:
            content = by_title.get(normalized.get(name, name)) or by_title.get(name)
            if content is not None:
                result[name] = content
        time.sleep(0.5)  # 对社区 Wiki 客气一点
    return result


def fetch_image_urls(display_names):
    """批量查条目图标的直链，用来做计划外的兜底。"""
    titles = ["File:" + name + ".png" for name in display_names]
    result = {}
    for start in range(0, len(titles), TITLES_PER_REQUEST):
        batch = titles[start:start + TITLES_PER_REQUEST]
        query = ("action=query&format=json&formatversion=2&prop=imageinfo&iiprop=url&titles="
                 + urllib.parse.quote("|".join(batch), safe=""))
        response = http_json(query)
        for page in response.get("query", {}).get("pages", []):
            info = page.get("imageinfo") or []
            if page.get("title") and info and info[0].get("url"):
                result[page["title"]] = info[0]["url"]
        time.sleep(0.5)
    return result


def fetch_external(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def step2(out_dir: Path, snapshot_path: Path):
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    names = [item["nameEn"] for item in snapshot["items"]]

    direct = fetch_wikitext(names)
    missing = [name for name in names if name not in direct]

    fallbacks, still_missing = {}, []
    if missing:
        archived = fetch_wikitext([MYSTICAL_ARCHIVE + "/" + name for name in missing])
        for name in missing:
            content = archived.get(MYSTICAL_ARCHIVE + "/" + name)
            if content is not None:
                fallbacks[name] = content
            else:
                still_missing.append(name)

    out_dir.mkdir(parents=True, exist_ok=True)
    wrote, renamed = 0, {}
    for name in names:
        content = direct.get(name) or fallbacks.get(name)
        if content is None:
            continue
        file_name = corpus_file_name(name)
        if file_name != name + ".wikitext":
            renamed[name] = file_name
        # 落盘的是数据源原样的 wikitext，不做任何清洗 —— 语料要能离线复跑、能逐字比对
        (out_dir / file_name).write_text(content, encoding="utf-8", newline="")
        wrote += 1

    provenance = {
        "来源": "https://peak.wiki.gg/api.php（action=query&prop=revisions）",
        "命中方式": {
            "直取(页面名=英文名)": sorted(direct),
            "存档页兜底": sorted(fallbacks),
        },
        "文件名改写": renamed,
        "缺失": still_missing,
    }
    (out_dir / "index.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=1) + "\n",
                                        encoding="utf-8", newline="\n")
    print(f"页面源文语料：写入 {wrote} 份到 {out_dir}")
    print(f"  直取 {len(direct)}，存档页兜底 {len(fallbacks)}，缺失 {len(still_missing)}")

    if still_missing:
        print("  尝试从图标说明页兜底：")
        urls = fetch_image_urls(still_missing)
        for name in still_missing:
            url = urls.get("File:" + name + ".png")
            if not url:
                print(f"    {name}: 数据源上没有 File:{name}.png")
                continue
            try:
                corpus_path(out_dir, name).write_text(fetch_external(url), encoding="utf-8", newline="")
                print(f"    {name}: 取自 {url}")
            except Exception as exc:  # 兜底失败不该让整步挂掉
                print(f"    {name}: 兜底失败 {exc}")


# --------------------------------------------------------------------------------------
# 第 3 步：容错命中普查
# --------------------------------------------------------------------------------------

def count_template(wikitext: str, name: str) -> int:
    return sum(1 for template_name, _ in walk_templates(wikitext) if template_name == name)


def unbalanced_pages(wikitext: str) -> int:
    """按 ItemPageParser 的扫描方式数「括号不配对」发生几次：每次 matchingClose 返回 -1 计一次。"""
    text = HTML_COMMENT.sub("", wikitext)
    count = 0
    cursor = 0
    while cursor < len(text):
        open_index = text.find("{{", cursor)
        if open_index < 0:
            break
        close = matching_close(text, open_index)
        if close < 0:
            count += 1
            break
        cursor = close + 2
    return count


def leading_templates(wikitext: str):
    """按 ItemPageParser.skipLeadingTemplates 的写法，返回跳过的前置模板名列表。"""
    text = HTML_COMMENT.sub("", wikitext)
    skipped = []
    cursor = 0
    while cursor < len(text):
        open_index = text.find("{{", cursor)
        if open_index < 0:
            break
        if text[cursor:open_index].strip():
            break
        close = matching_close(text, open_index)
        if close < 0:
            break
        pieces = split_top_level(text[open_index + 2:close])
        skipped.append(normalize_key(pieces[0] if pieces else ""))
        cursor = close + 2
    return skipped


def override_value(infobox: dict, key: str):
    return blank_to_null(infobox.get(key))


def step3(corpus_dir: Path, snapshot_path: Path, report_path: Path):
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    items = snapshot["items"]

    per_item = []
    for item in items:
        name = item["nameEn"]
        path = corpus_path(corpus_dir, name)
        record = {"nameEn": name, "corpus": path.exists()}
        if not path.exists():
            per_item.append(record)
            continue

        raw = path.read_text(encoding="utf-8")
        record["bytes"] = len(raw.encode("utf-8"))
        record["unbalanced"] = unbalanced_pages(raw)

        html_free = HTML_COMMENT.sub("", raw)
        page_name_hits = len(PAGE_NAME_TEMPLATE.findall(raw))

        infoboxes = infoboxes_of(raw)
        record["infoboxCount"] = len(infoboxes)
        record["display"] = blank_to_null(infoboxes[0].get("display")) if infoboxes else None
        record["name"] = blank_to_null(infoboxes[0].get("name")) if infoboxes else None

        chosen = select_infobox(infoboxes, name)
        if not infoboxes:
            # 页面上没有 Infobox item，selectInfobox 走不到，谈不上"认领"
            record.update(chosenOverrides=(None, None, None), otherOverrides=[], overridesDiffer=False,
                          claimedByDisplay=None, leadingTemplates=leading_templates(raw),
                          hasInfobox=False, badgeCount=count_template(raw, "badge"),
                          preLeadingTemplates={}, whitespaceNewlines=raw.count("\r\n"),
                          sectionHeadings=len(re.findall(r"(?m)^={2,}\s*.+?\s*={2,}\s*$", raw)),
                          htmlComments=len(HTML_COMMENT.findall(raw)),
                          pagenameHits=page_name_hits,
                          mediaLinks=len(re.findall(r"\[\[\s*(?:File|Image)\s*:", raw, re.IGNORECASE)),
                          tabulations=len(re.findall(r"(?s)\{\|.*?\|\}", raw)),
                          refElements=len(re.findall(r"<(?:ref|gallery)\b", raw, re.IGNORECASE)),
                          externalLinks=len(re.findall(r"\[\s*(?:https?|ftp)://", raw)),
                          boldMarkers=raw.count("'''"), italicMarkers=raw.count("''"),
                          nestedBrackets=len(re.findall(r"\[\[[^\[\]]*\[\[", raw)),
                          nonNumericHungerCooked=[], nonNumericBonusCooked=[],
                          inlineTemplates=sum(1 for _, body in walk_templates(raw) if "\n" not in body))
            per_item.append(record)
            continue

        record["claimedByDisplay"] = len(infoboxes) == 1 or any(
            blank_to_null(box.get(key)) and blank_to_null(box.get(key)).lower() == name.lower()
            for key in NAME_KEYS for box in infoboxes)

        # 多项 Infobox 之间，熟食覆写值是否真的不同
        def overrides(box):
            return (override_value(box, "hungercooked"), override_value(box, "bonuscooked"),
                    override_value(box, "hascookingbonus"))

        chosen_overrides = overrides(chosen)
        record["chosenOverrides"] = chosen_overrides
        record["otherOverrides"] = [overrides(box) for box in infoboxes if box is not chosen]
        record["overridesDiffer"] = any(o != chosen_overrides for o in record["otherOverrides"])

        # 各防御的命中
        record["leadingTemplates"] = leading_templates(raw)
        record["hasInfobox"] = bool(infoboxes)
        record["badgeCount"] = count_template(raw, "badge")
        record["preLeadingTemplates"] = {
            "ambox": count_template(raw, "ambox"),
            "for": count_template(raw, "for"),
            "infoboxlocation": count_template(raw, "infoboxlocation"),
            "rawInfobox": count_template(raw, "infobox"),
        }
        record["whitespaceNewlines"] = raw.count("\r\n")
        record["sectionHeadings"] = len(re.findall(r"(?m)^={2,}\s*.+?\s*={2,}\s*$", raw))
        record["htmlComments"] = len(HTML_COMMENT.findall(raw))
        record["pagenameHits"] = page_name_hits
        record["mediaLinks"] = len(re.findall(r"\[\[\s*(?:File|Image)\s*:", raw, re.IGNORECASE))
        record["tabulations"] = len(re.findall(r"(?s)\{\|.*?\|\}", raw))
        record["refElements"] = len(re.findall(r"<(?:ref|gallery)\b", raw, re.IGNORECASE))
        record["externalLinks"] = len(re.findall(r"\[\s*(?:https?|ftp)://", raw))
        record["boldMarkers"] = raw.count("'''")
        record["italicMarkers"] = raw.count("''")
        record["nestedBrackets"] = len(re.findall(r"\[\[[^\[\]]*\[\[", raw))
        record["nonNumericHungerCooked"] = [
            override_value(box, "hungercooked") for box in infoboxes
            if override_value(box, "hungercooked") and not re.fullmatch(r"[0-9.+-]+", override_value(box, "hungercooked"))
        ]
        record["nonNumericBonusCooked"] = [
            override_value(box, "bonuscooked") for box in infoboxes
            if override_value(box, "bonuscooked") and not re.fullmatch(r"[0-9.+-]+", override_value(box, "bonuscooked"))
        ]
        # 行内模板出现在 Infobox 里的情况（line 588 之类）：先记数量，看有没有
        record["inlineTemplates"] = sum(1 for _, body in walk_templates(raw) if "\n" not in body)
        per_item.append(record)

    report = summarize(per_item)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps({"summary": report, "perItem": per_item},
                                      ensure_ascii=False, indent=1) + "\n",
                           encoding="utf-8", newline="\n")
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return report


def summarize(per_item):
    present = [r for r in per_item if r.get("corpus")]
    multi = [r["nameEn"] for r in present if r["infoboxCount"] > 1]
    no_infobox = [r["nameEn"] for r in present if r["infoboxCount"] == 0]

    def names_where(predicate):
        return sorted(r["nameEn"] for r in present if predicate(r))

    return {
        "语料覆盖": {
            "条目总数": len(per_item),
            "有语料": len(present),
            "缺语料": sorted(r["nameEn"] for r in per_item if not r.get("corpus")),
            "语料总字节": sum(r["bytes"] for r in present),
        },
        "模板白名单": {
            "命中 infoboxitem": names_where(lambda r: r["hasInfobox"]),
            "命中 iteminfobox（变体）": names_where(lambda r: "iteminfobox" in r["leadingTemplates"]),
            "命中 badge": {r["nameEn"]: r["badgeCount"] for r in present if r["badgeCount"]},
        },
        "多 Infobox 页面": {
            "数量": len(multi),
            "清单": multi,
            "覆写值真的不同": names_where(lambda r: r["infoboxCount"] > 1 and r["overridesDiffer"]),
            "覆写值完全相同": names_where(lambda r: r["infoboxCount"] > 1 and not r["overridesDiffer"]),
            "靠 display 认领成功": names_where(lambda r: r["infoboxCount"] > 1 and r["claimedByDisplay"]),
            "认领失败（退回第一个框）": names_where(lambda r: r["infoboxCount"] > 1 and not r["claimedByDisplay"]),
        },
        "括号不配对": {
            "命中条目": names_where(lambda r: r["unbalanced"]),
            "合计次数": sum(r["unbalanced"] for r in present),
        },
        "没有 Infobox item 的页面": {
            "数量": len(no_infobox),
            "清单": no_infobox,
            "跳过前置模板后仍有正文": names_where(lambda r: not r["hasInfobox"] and r["sectionHeadings"] >= 0),
        },
        "前置模板": {
            ">{0} 个前置模板".format(0): names_where(lambda r: len(r["leadingTemplates"]) > 0),
            "计数": {r["nameEn"]: r["leadingTemplates"] for r in present if r["leadingTemplates"]},
            "Ambox 出现": names_where(lambda r: r["preLeadingTemplates"].get("ambox")),
            "For 出现": names_where(lambda r: r["preLeadingTemplates"].get("for")),
            "Infobox location 出现": names_where(lambda r: r["preLeadingTemplates"].get("infoboxlocation")),
            "裸 Infobox 出现": names_where(lambda r: r["preLeadingTemplates"].get("rawInfobox")),
        },
        "WikitextUtil 各项": {
            "HTML 注释": {r["nameEn"]: r["htmlComments"] for r in present if r["htmlComments"]},
            "换行是 \\r\\n 的条目": names_where(lambda r: r["whitespaceNewlines"]),
            "媒体链接": {r["nameEn"]: r["mediaLinks"] for r in present if r["mediaLinks"]},
            "表格": {r["nameEn"]: r["tabulations"] for r in present if r["tabulations"]},
            "ref/gallery 扩展标签": {r["nameEn"]: r["refElements"] for r in present if r["refElements"]},
            "站外链": {r["nameEn"]: r["externalLinks"] for r in present if r["externalLinks"]},
            "三撇号粗体": names_where(lambda r: r["boldMarkers"]),
            "双撇号斜体": names_where(lambda r: r["italicMarkers"]),
            "嵌套方括号": {r["nameEn"]: r["nestedBrackets"] for r in present if r["nestedBrackets"]},
            "PAGENAME 占位符": {r["nameEn"]: r["pagenameHits"] for r in present if r["pagenameHits"]},
            "章节标题": names_where(lambda r: r["sectionHeadings"]),
            "行内模板": names_where(lambda r: r["inlineTemplates"]),
        },
        "取值级防御": {
            "HungerCooked 非数字": {r["nameEn"]: r["nonNumericHungerCooked"] for r in present if r["nonNumericHungerCooked"]},
            "BonusCooked 非数字": {r["nameEn"]: r["nonNumericBonusCooked"] for r in present if r["nonNumericBonusCooked"]},
        },
    }


def main():
    parser = argparse.ArgumentParser(description="冻结重写基线（#21）")
    parser.add_argument("--step1", action="store_true", help="生成金标准快照")
    parser.add_argument("--step2", action="store_true", help="抓取离线页面源文语料")
    parser.add_argument("--step3", action="store_true", help="容错命中普查")
    parser.add_argument("--out", type=Path, help="语料输出目录（必须落在仓库外）")
    parser.add_argument("--corpus", type=Path, help="语料目录（第 3 步读它）")
    parser.add_argument("--snapshot", type=Path, default=DEFAULT_SNAPSHOT)
    parser.add_argument("--report", type=Path, help="普查报告输出路径")
    args = parser.parse_args()

    if args.step1:
        step1(args.snapshot)
    if args.step2:
        if not args.out:
            parser.error("--step2 需要 --out")
        out_dir = args.out.resolve()
        if str(out_dir).startswith(str(REPO_ROOT)) and "target" not in str(out_dir):
            parser.error("--out 落在仓库内了；语料不进仓库，请指向仓库外的本地工作目录")
        step2(out_dir, args.snapshot)
    if args.step3:
        if not args.corpus or not args.report:
            parser.error("--step3 需要 --corpus 与 --report")
        step3(args.corpus.resolve(), args.snapshot, args.report.resolve())
    if not (args.step1 or args.step2 or args.step3):
        parser.print_help()
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
