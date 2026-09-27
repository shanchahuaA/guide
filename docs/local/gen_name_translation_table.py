# -*- coding: utf-8 -*-
"""
生成「名称翻译对照表」工作文件（docs/local/name-translation.CSV，不进仓库）。

只读本地快照，不发任何网络请求：
- 条目英文名：Cargo 全量快照（134 行）的 display 原值（对齐 CargoItemRow 的 display / 字段别名 page）
- 标签字典：TagDictionary.java 的六张 Map 直读（type/biome/rarity/source/location/flag）

生成物带 UTF-8 BOM，Excel 直接双击不乱码。
"""
import csv
import io
import json
import os
import re

SNAPSHOT = os.path.expandvars(r"%TEMP%\cargo_items.json")
DICT_JAVA = r"guide/src/main/java/org/example/guide/crawler/TagDictionary.java"
OUT = r"docs/local/name-translation.CSV"

DIMENSIONS = [
    ("type", "TYPE_ZH"),
    ("biome", "BIOME_ZH"),
    ("rarity", "RARITY_ZH"),
    ("source", "SOURCE_ZH"),
    ("location", "LOCATION_ZH"),
    ("flag", "FLAG_ZH"),
]

HEADER_LINES = [
    "# 工作文件，不提交（见 .gitignore）。本文件是 name_zh 翻译填充的校对底稿，不是仓库文档。",
    "# 生成日期：2026-09-26　来源：Cargo Items 全量快照 134 行 + TagDictionary.java 六张字典（离线生成，未发网络请求）",
    "# 用法：校对/修改 name_zh 列（条目行已由 gen_peaks_terms.py 按 PEAK 官方译名填好）；改完告诉 agent 按本表填充 item.name_zh。",
    "# 表头五列：kind, dim, en, name_zh, note —— en 列不要动（它是对回数据源 / 字典的键）。",
]


def read_dictionaries():
    """
    从 TagDictionary.java 直读六张 Map（Map.ofEntries(entry(...)) 的行形状）。

    五张维度字典的键是字面量；flag 那张的键用的是类里的常量名（entry(FLAG_COOKABLE, "可烹饪")），
    所以先把常量声明读成对照表，再把常量键还原成字面量 —— 只认字面量的话 flag 会静默少两行。
    """
    with io.open(DICT_JAVA, encoding="utf-8") as f:
        text = f.read()

    constants = dict(re.findall(r'public static final String (\w+) = "([^"]*)";', text))

    result = {}
    for dimension, field in DIMENSIONS:
        block = re.search(r"private static final Map<String, String> %s = Map\.ofEntries\((.*?)\);" % field,
                          text, re.S)
        if block is None:
            raise SystemExit("在 %s 里找不到 %s 的定义" % (DICT_JAVA, field))
        pairs = []
        for key, value in re.findall(r'entry\(([^,]+),\s*"([^"]*)"\)', block.group(1)):
            key = key.strip()
            if key.startswith('"'):
                key = key.strip('"')
            else:
                if key not in constants:
                    raise SystemExit("%s 的 %s 里出现了解不出的键：%s" % (DICT_JAVA, field, key))
                key = constants[key]
            pairs.append((key, value))
        if not pairs:
            raise SystemExit("%s 的 %s 解析出 0 行，字典解析规则大概已经跟代码对不上了" % (DICT_JAVA, field))
        result[dimension] = pairs
    return result


def read_item_names():
    with io.open(SNAPSHOT, encoding="utf-8") as f:
        rows = json.load(f)["cargoquery"]
    names = []
    for row in rows:
        fields = row.get("title", row)
        by_lower = {str(k).lower(): v for k, v in fields.items()}
        display = by_lower.get("display")
        if display is None or not str(display).strip():
            raise SystemExit("快照里有一行没有 display，与 CargoItemRow 的收口规则冲突")
        names.append(str(display).strip())
    # display 在数据源里是唯一的（毒蘑菇变体本来就是独立行），这里保序去重以防万一
    seen, unique = set(), []
    for name in names:
        if name not in seen:
            seen.add(name)
            unique.append(name)
    return unique


def main():
    dictionaries = read_dictionaries()
    item_names = read_item_names()

    lines = HEADER_LINES
    body = io.StringIO()
    writer = csv.writer(body, lineterminator="\r\n")
    writer.writerow(["kind", "dim", "en", "name_zh", "note"])

    for name in item_names:
        writer.writerow(["item", "item", name, "", "图鉴条目英文名（数据源 display）；中文名由 gen_peaks_terms.py 按官方译名回填，可直接改"])

    for dimension, _ in DIMENSIONS:
        for key, value in dictionaries[dimension]:
            writer.writerow(["dict", dimension, key, value,
                             "标签字典（TagDictionary.%s_ZH）" % dimension.upper()])

    with io.open(OUT, "w", encoding="utf-8-sig", newline="") as f:
        f.write("\n".join(lines) + "\n")
        f.write(body.getvalue())

    counts = {d: len(dictionaries[d]) for d, _ in DIMENSIONS}
    data_rows = len(item_names) + sum(counts.values())
    print("条目 %d 行" % len(item_names))
    print("字典 %d 行  %s" % (sum(counts.values()), counts))
    print("数据行 %d（+ 表头 1，CSV 记录共 %d）" % (data_rows, data_rows + 1))
    print("文件物理行 %d（+ 注释 %d）" % (data_rows + 1 + len(HEADER_LINES),
                                          len(HEADER_LINES)))


if __name__ == "__main__":
    main()
