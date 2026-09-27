"""crawl_items 的单元测试（stdlib unittest，零新增依赖）。

只测纯函数接缝：数据源行收口、标签/效果转换、字典未知取值、熟食公式、location 剥标记。
拉取与落库靠 `crawl_items.py --check` 与真跑一次验证，不在这里联网/写库。

    python guide/tools/test_crawl_items.py
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_items as crawler  # noqa: E402


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
            crawler.convert(crawler.CargoRow.from_map({"display": "  "}))

    def test_food_cooked_formula_and_cookable(self):
        item, unknown = crawler.convert(crawler.CargoRow.from_map({
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
        item, _ = crawler.convert(crawler.CargoRow.from_map({
            "display": "Mushroom", "type": "Food, Mushroom", "hunger": "-15", "poison": "20",
        }))
        self.assertEqual(effect_of(item, "BONUS_COOKED")["value"], 10.0)
        # 非浆果的毒原样保留（含附属值）
        poison_cooked = effect_of(item, "POISON_COOKED")
        self.assertEqual(poison_cooked["value"], 20.0)

    def test_berry_poison_cooked_is_explicit_zero_without_aux(self):
        item, _ = crawler.convert(crawler.CargoRow.from_map({
            "display": "Green Crispberry", "type": "Food, Berry",
            "poison": "20", "poisonTime": "4",
        }))
        self.assertEqual(effect_of(item, "POISON")["duration"], 4.0)
        poison_cooked = effect_of(item, "POISON_COOKED")
        self.assertEqual(poison_cooked["value"], 0.0)
        self.assertIsNone(poison_cooked["duration"])
        self.assertIsNone(poison_cooked["startDelay"])

    def test_non_food_has_no_cooked_and_no_cookable(self):
        item, _ = crawler.convert(crawler.CargoRow.from_map({
            "display": "First Aid Kit", "type": "Consumable", "uses": "1",
        }))
        self.assertIsNone(effect_of(item, "HUNGER_COOKED"))
        self.assertNotIn("cookable", tags_of(item, "flag"))

    def test_only_explicit_zeros_is_not_cookable(self):
        self.assertFalse(crawler.contains_non_zero([{"value": 0.0}, {"value": None}]))
        self.assertTrue(crawler.contains_non_zero([{"value": 0.0}, {"value": 10.0}]))

    def test_unknown_dictionary_value_kept_with_empty_zh(self):
        item, unknown = crawler.convert(crawler.CargoRow.from_map({
            "display": "New Thing", "type": "Brand New Category", "weight": "1",
        }))
        self.assertEqual(unknown, ["type=Brand New Category"])
        self.assertEqual(item["tag"][0]["value"], "Brand New Category")
        self.assertIsNone(item["tag"][0]["nameZh"])

    def test_location_markup_stripped_and_campfire_aliased(self):
        item, _ = crawler.convert(crawler.CargoRow.from_map({
            "display": "Backpack", "type": "Equipment",
            "location": "[[Campfire]], In [[Luggage]], [[Peak (biome)|Peak]], [[Peak_(biome)#Stone_Scout|Stone Scout]]",
        }))
        self.assertEqual(tags_of(item, "location"), ["Campfires", "In Luggage", "Peak", "Stone Scout"])

    def test_removed_flag_is_stored(self):
        item, _ = crawler.convert(crawler.CargoRow.from_map({
            "display": "Warp Compass", "type": "Misc", "removed": "1",
        }))
        self.assertIn("removed", tags_of(item, "flag"))

    def test_uses_zero_is_skipped(self):
        item, _ = crawler.convert(crawler.CargoRow.from_map({
            "display": "Static Thing", "type": "Misc", "uses": "0",
        }))
        self.assertIsNone(effect_of(item, "USES"))


if __name__ == "__main__":
    unittest.main()
