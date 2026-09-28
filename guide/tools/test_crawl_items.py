"""crawl_items / page_source / item_icons 的单元测试（stdlib unittest，零新增依赖）。

只测纯函数与注入了假 HTTP 的接缝：数据源行收口、标签/效果转换、字典未知取值、
熟食公式与覆写、location 剥标记、页面源文的覆写值 / 描述 / 成就解析，
以及图标文件名规则与"单张失败不中断整批"。拉取与落库靠 `crawl_items.py --check`
与真跑一次验证，不在这里联网/写库。

    python guide/tools/test_crawl_items.py
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_items as crawler  # noqa: E402
import item_icons  # noqa: E402
import page_source  # noqa: E402

GOLDEN = (Path(__file__).resolve().parents[1]
          / "src" / "test" / "resources" / "crawler" / "golden-items.json")


def convert(row):
    """不带页面源文的转换（等价于源文取不到时的降级路径）。"""
    return crawler.convert(row, page_source.EMPTY_PARAMS)


def tags_of(item, dimension):
    return [t["value"] for t in item["tag"] if t["code"] == dimension]


def effect_of(item, code):
    return next((e for e in item["effect"] if e["code"] == code), None)


class CargoRowTest(unittest.TestCase):
    def test_blank_numeric_is_null(self):
        row = crawler.CargoRow.from_map({"hunger": ""})
        self.assertIsNone(row.hunger)

    def test_non_numeric_raises(self):
        with self.assertRaises(ValueError):
            crawler.CargoRow.from_map({"hunger": "abc"})

    def test_removed_accepts_one_and_true(self):
        self.assertTrue(crawler.CargoRow.from_map({"removed": "1"}).removed)
        self.assertTrue(crawler.CargoRow.from_map({"removed": True}).removed)
        self.assertFalse(crawler.CargoRow.from_map({"removed": ""}).removed)

    def test_multi_value_accepts_list(self):
        row = crawler.CargoRow.from_map({"type": ["Food", "Berry"]})
        self.assertEqual(row.type, "Food, Berry")


class ConvertTest(unittest.TestCase):
    def test_missing_display_raises(self):
        with self.assertRaises(ValueError):
            convert(crawler.CargoRow.from_map({"display": "  "}))

    def test_food_cooked_formula_and_cookable(self):
        item, unknown = convert(crawler.CargoRow.from_map({
            "display": "Hot Dog", "type": "Food", "biome": "Gloom", "rarity": "Rare",
            "hunger": "-30", "bonus": "30", "weight": "25", "uses": "1",
        }))
        self.assertEqual(unknown, [])
        self.assertEqual(tags_of(item, "biome"), ["Gloom"])
        self.assertEqual(tags_of(item, "rarity"), ["Rare"])
        self.assertEqual(tags_of(item, "flag"), ["cookable"])
        self.assertEqual(effect_of(item, "HUNGER")["value"], -30.0)
        self.assertEqual(effect_of(item, "HUNGER_COOKED")["value"], -60.0)
        self.assertEqual(effect_of(item, "BONUS_COOKED")["value"], 45.0)

    def test_bonus_fallback_when_no_raw_bonus(self):
        item, _ = convert(crawler.CargoRow.from_map({
            "display": "Mushroom", "type": "Food, Mushroom", "hunger": "-15", "poison": "20",
        }))
        self.assertEqual(effect_of(item, "BONUS_COOKED")["value"], 10.0)
        # 非浆果的毒原样保留（含附属值）
        poison_cooked = effect_of(item, "POISON_COOKED")
        self.assertEqual(poison_cooked["value"], 20.0)

    def test_berry_poison_cooked_is_explicit_zero_without_aux(self):
        item, _ = convert(crawler.CargoRow.from_map({
            "display": "Green Crispberry", "type": "Food, Berry",
            "poison": "20", "poisonTime": "4",
        }))
        self.assertEqual(effect_of(item, "POISON")["duration"], 4.0)
        poison_cooked = effect_of(item, "POISON_COOKED")
        self.assertEqual(poison_cooked["value"], 0.0)
        self.assertIsNone(poison_cooked["duration"])
        self.assertIsNone(poison_cooked["startDelay"])

    def test_non_food_has_no_cooked_and_no_cookable(self):
        item, _ = convert(crawler.CargoRow.from_map({
            "display": "First Aid Kit", "type": "Consumable", "uses": "1",
        }))
        self.assertIsNone(effect_of(item, "HUNGER_COOKED"))
        self.assertNotIn("cookable", tags_of(item, "flag"))

    def test_only_explicit_zeros_is_not_cookable(self):
        self.assertFalse(crawler.contains_non_zero([{"value": 0.0}, {"value": None}]))
        self.assertTrue(crawler.contains_non_zero([{"value": 0.0}, {"value": 10.0}]))

    def test_unknown_dictionary_value_kept_with_empty_zh(self):
        item, unknown = convert(crawler.CargoRow.from_map({
            "display": "New Thing", "type": "Brand New Category", "weight": "1",
        }))
        self.assertEqual(unknown, ["type=Brand New Category"])
        self.assertEqual(item["tag"][0]["value"], "Brand New Category")
        self.assertIsNone(item["tag"][0]["nameZh"])

    def test_location_markup_stripped_and_campfire_aliased(self):
        item, _ = convert(crawler.CargoRow.from_map({
            "display": "Backpack", "type": "Equipment",
            "location": "[[Campfire]], In [[Luggage]], [[Peak (biome)|Peak]], [[Peak_(biome)#Stone_Scout|Stone Scout]]",
        }))
        self.assertEqual(tags_of(item, "location"), ["Campfires", "In Luggage", "Peak", "Stone Scout"])

    def test_removed_flag_is_stored(self):
        item, _ = convert(crawler.CargoRow.from_map({
            "display": "Warp Compass", "type": "Misc", "removed": "1",
        }))
        self.assertIn("removed", tags_of(item, "flag"))

    def test_uses_zero_is_skipped(self):
        item, _ = convert(crawler.CargoRow.from_map({
            "display": "Static Thing", "type": "Misc", "uses": "0",
        }))
        self.assertIsNone(effect_of(item, "USES"))

    def test_cooked_override_wins_over_formula(self):
        row = crawler.CargoRow.from_map({"display": "Cooked Bird", "type": "Food", "hunger": "10"})
        item, _ = crawler.convert(row, page_source.CookParams(hunger_cooked=-80.0))
        self.assertEqual(effect_of(item, "HUNGER_COOKED")["value"], -80.0)

    def test_has_cooking_bonus_no_suppresses_cooked_values(self):
        row = crawler.CargoRow.from_map({"display": "The Early Worm", "type": "Food", "hunger": "-5"})
        item, _ = crawler.convert(row, page_source.CookParams(has_cooking_bonus="no"))
        self.assertIsNone(effect_of(item, "HUNGER_COOKED"))
        self.assertNotIn("cookable", tags_of(item, "flag"))


HOT_DOG = "\n".join([
    "{{Infobox item",
    "| Type = Food, Natural food",
    "| Hunger = -30",
    "| Cooking-notes = Adds '''10s''' to invulnerability.",
    "| List-notes = *'''Source''': Campfires.",
    "}}",
    "",
    "The '''{{PAGENAME}}''' is a [[natural food]] in {{Peak game}}.",
    "",
    "Eating grants the {{Badge|Competitive Eating}}.",
    "",
    "== Trivia ==",
    "* not part of the description",
])


class PageSourceTest(unittest.TestCase):
    def test_params_read_override_and_notes(self):
        params = page_source.read_params("{{Infobox item\n| HungerCooked = -80\n| HasCookingBonus = No }}", "X")
        self.assertEqual(params.hunger_cooked, -80.0)
        self.assertTrue(params.suppresses_cooking_bonus())

    def test_params_non_numeric_raises(self):
        with self.assertRaises(ValueError):
            page_source.read_params("{{Infobox item\n| HungerCooked = abc }}", "X")

    def test_params_value_with_html_comment_is_clean(self):
        # 注释落在取值里时先剥掉，否则数字会带上注释文本、被当成解析失败
        params = page_source.read_params(
            "{{Infobox item\n| Hunger = -30<!-- 测试用 -->\n| HungerCooked = -80 }}", "X")
        self.assertEqual(params.hunger_cooked, -80.0)

    def test_params_without_infobox_is_empty(self):
        self.assertIsNone(page_source.read_params("#REDIRECT [[Bugle Shroom]]", "X").hunger_cooked)

    def test_page_text_prose_and_achievement(self):
        description, achievement = page_source.read_page_text(HOT_DOG, "Hot Dog")
        self.assertEqual(achievement, "Competitive Eating")
        self.assertTrue(description.startswith("The Hot Dog is a natural food in PEAK."))
        self.assertIn("Source: Campfires.", description)
        self.assertTrue(description.endswith("烹饪：Adds 10s to invulnerability."))
        self.assertNotIn("Trivia", description)

    def test_badge_matched_by_template_name_not_substring(self):
        # Ambox 里也有 "Badge" 字样，按模板名取才不会凭空造出成就
        text = "{{Ambox|icon = Speed Climber Badge.png}}\n\nhello"
        self.assertIsNone(page_source.read_page_text(text, "X")[1])

    def test_strip_markup_templates_and_links(self):
        self.assertEqual(page_source.strip_markup("{{Item|Marshmallow}}", None), "Marshmallow")
        self.assertEqual(page_source.strip_markup("in {{Peak game}} now", None), "in PEAK now")
        self.assertEqual(page_source.strip_markup("other than {{Weight}}", None), "other than Weight")
        self.assertEqual(page_source.strip_markup("a [[Gloom|dark place]]", None), "a dark place")


def urls_for(mapping):
    """假的直链查询：只给 mapping 里列出的英文名直链，其余当"数据源上没有这个文件"。

    与 item_icons.fetch_direct_urls 同一契约：查不到直链的那些也要记进失败明细。
    """
    def resolve(requested):
        urls = {display: mapping[display] for display in requested if display in mapping}
        failures = [{"nameEn": display, "reason": "数据源上没有这个文件:" + item_icons.wiki_title(display)}
                    for display in requested if display not in mapping]
        return urls, failures
    return resolve


def fake_fetch(content=b"\x89PNG", fail=()):
    """假的图片下载：默认每次都给同一份字节，``fail`` 里列出的 URL 一律抛错。"""
    def fetch(url):
        if url in fail:
            raise OSError("连不上")
        return content
    return fetch


class IconTest(unittest.TestCase):
    def test_file_name_rewrites_windows_unsafe_char(self):
        # 数据源上真有一个 File:Bugle?.png：? 既建不出 Windows 文件，又会被 URL 当成 query 的起点
        self.assertEqual(item_icons.file_name("Bugle?"), "Bugle_.png")

    def test_file_name_keeps_already_safe_name(self):
        self.assertEqual(item_icons.file_name("Hot Dog"), "Hot_Dog.png")
        self.assertEqual(item_icons.file_name("Scout's Ambition"), "Scout's_Ambition.png")
        self.assertEqual(item_icons.file_name("Bugle Shroom (Poisonous)"), "Bugle_Shroom_(Poisonous).png")
        self.assertEqual(item_icons.file_name("Anti-Zooka"), "Anti-Zooka.png")

    def test_icon_path_uses_the_app_url_prefix(self):
        self.assertEqual(item_icons.icon_path("Hot Dog"), "/icons/Hot_Dog.png")

    def test_wiki_title_keeps_raw_display(self):
        # 查询用的标题是数据源上的原名（不改写），改写的只是本地文件名
        self.assertEqual(item_icons.wiki_title("Bugle?"), "File:Bugle?.png")

    def test_golden_icon_column_is_reproduced(self):
        # 金标准里的 icon 列是重写前的采集写进去的；这条把"文件名规则与它同一口径"钉在数据上
        golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
        diffs = [(item["nameEn"], item["icon"], item_icons.icon_path(item["nameEn"]))
                 for item in golden["items"] if item_icons.icon_path(item["nameEn"]) != item["icon"]]
        self.assertEqual(diffs, [])
        self.assertEqual(len(golden["items"]), 134)

    def test_convert_fills_icon_column(self):
        item, _ = convert(crawler.CargoRow.from_map({"display": "Hot Dog", "type": "Food"}))
        self.assertEqual(item["icon"], "/icons/Hot_Dog.png")

    def test_download_writes_file_and_returns_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = item_icons.download_all(
                ["Hot Dog"], tmp,
                resolve_urls=urls_for({"Hot Dog": "https://x/a.png"}),
                fetch_bytes=fake_fetch(b"\x89PNG-body"))
            self.assertEqual(result.path_by_display, {"Hot Dog": "/icons/Hot_Dog.png"})
            self.assertEqual(result.failures, [])
            self.assertEqual((Path(tmp) / "Hot_Dog.png").read_bytes(), b"\x89PNG-body")

    def test_single_failure_does_not_stop_the_batch(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = item_icons.download_all(
                ["Good One", "Bad One"], tmp,
                resolve_urls=urls_for({"Good One": "https://x/good.png", "Bad One": "https://x/bad.png"}),
                fetch_bytes=fake_fetch(fail=("https://x/bad.png",)))
            self.assertEqual(list(result.path_by_display), ["Good One"])
            self.assertEqual([f["nameEn"] for f in result.failures], ["Bad One"])
            self.assertIn("连不上", result.failures[0]["reason"])
            # 旁边已经下好的那张既没被回滚、也没被清掉
            self.assertTrue((Path(tmp) / "Good_One.png").exists())

    def test_missing_file_on_source_is_recorded_and_icon_left_blank(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = item_icons.download_all(
                ["Hot Dog"], tmp, resolve_urls=urls_for({}), fetch_bytes=fake_fetch())
            self.assertEqual(result.path_by_display, {})
            self.assertIn("数据源上没有这个文件:File:Hot Dog.png", result.failures[0]["reason"])

    def test_empty_body_is_a_failure_not_an_empty_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = item_icons.download_all(
                ["Hot Dog"], tmp,
                resolve_urls=urls_for({"Hot Dog": "https://x/a.png"}),
                fetch_bytes=fake_fetch(b""))
            self.assertEqual(result.path_by_display, {})
            self.assertIn("响应体为空", result.failures[0]["reason"])
            self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_retry_once_recovers_from_a_transient_failure(self):
        calls = []

        def flaky(url):
            calls.append(url)
            if len(calls) == 1:
                raise OSError("第一次抖动")
            return b"png"

        with tempfile.TemporaryDirectory() as tmp:
            result = item_icons.download_all(
                ["Hot Dog"], tmp,
                resolve_urls=urls_for({"Hot Dog": "https://x/a.png"}),
                fetch_bytes=flaky)
            self.assertEqual(list(result.path_by_display), ["Hot Dog"])
            self.assertEqual(len(calls), 2)

    def test_unsafe_name_is_rewritten_and_warned(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = item_icons.download_all(
                ["Bugle?"], tmp,
                resolve_urls=urls_for({"Bugle?": "https://x/bugle.png"}),
                fetch_bytes=fake_fetch())
            self.assertEqual(list(result.path_by_display), ["Bugle?"])
            self.assertTrue((Path(tmp) / "Bugle_.png").exists())
            self.assertIn("Bugle?", result.warnings[0])

    def test_uncreatable_directory_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            blocked = Path(tmp) / "icons"
            blocked.write_text("not a directory", encoding="utf-8")
            with self.assertRaises(OSError):
                item_icons.download_all(["Hot Dog"], blocked,
                                        resolve_urls=urls_for({"Hot Dog": "https://x/a.png"}),
                                        fetch_bytes=fake_fetch())

    def test_batch_query_failure_is_isolated_to_that_batch(self):
        calls = []

        def fake_http(query):
            calls.append(query)
            if "Item%200" in query:        # 第一包（Item 0 起）：这一包彻底查不动
                raise RuntimeError("被 Cloudflare 拦下")
            return {"query": {"pages": [
                {"title": "File:Item 50.png", "imageinfo": [{"url": "https://x/50.png"}]}]}}

        urls, failures = item_icons.fetch_direct_urls([f"Item {i}" for i in range(51)], http_json=fake_http)
        self.assertEqual(len(calls), 3)     # 51 个标题分两包，第一包失败后重试一次
        self.assertEqual([f["nameEn"] for f in failures], [f"Item {i}" for i in range(50)])
        self.assertIn("被 Cloudflare 拦下", failures[0]["reason"])
        self.assertEqual(urls, {"Item 50": "https://x/50.png"})

    def test_direct_url_query_falls_back_to_the_requested_title(self):
        # normalized 把请求标题指去了响应里没有的写法时，仍要按请求的原写法再找一次 ——
        # 少了这一步会凭空造出一条"数据源上没有这个文件"的失败，条目图标跟着留空
        def fake_http(query):
            return {"query": {
                "normalized": [{"from": "File:Hot Dog.png", "to": "File:Hot Dog (item).png"}],
                "pages": [{"title": "File:Hot Dog.png", "imageinfo": [{"url": "https://x/hot.png"}]}]}}

        urls, failures = item_icons.fetch_direct_urls(["Hot Dog"], http_json=fake_http)
        self.assertEqual(urls, {"Hot Dog": "https://x/hot.png"})
        self.assertEqual(failures, [])

    def test_direct_url_query_follows_title_normalization(self):
        def fake_http(query):
            return {"query": {
                "normalized": [{"from": "File:Hot Dog.png", "to": "File:Hot Dog.png"}],
                "pages": [
                    {"title": "File:Hot Dog.png", "imageinfo": [{"url": "https://x/hot.png"}]},
                    {"title": "File:Gone.png"},     # 文件不存在：没有 imageinfo
                ]}}

        urls, failures = item_icons.fetch_direct_urls(["Hot Dog", "Gone"], http_json=fake_http)
        self.assertEqual(urls, {"Hot Dog": "https://x/hot.png"})
        self.assertEqual([f["nameEn"] for f in failures], ["Gone"])


if __name__ == "__main__":
    unittest.main()
