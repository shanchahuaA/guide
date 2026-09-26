package org.example.guide.utils;

import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 两个计算值（slug / primaryType）的口径回归。
 *
 * 不加载 Spring 上下文：两条规则是纯函数,输入只有 nameEn 与 type 标签,
 * 跑测试不该需要 MySQL 和 Redis。
 */
class ItemFieldsTest {

    @Test
    void slugFollowsIconFileNameRuleAndLowercases() {
        assertEquals("hot_dog", ItemFields.slugOf(item("Hot Dog")));
        assertEquals("bugle", ItemFields.slugOf(item("Bugle")));
        // ? 与空格一样落进白名单外,被换成下划线 —— 这是 slug 唯一性要的那一手
        assertEquals("bugle_", ItemFields.slugOf(item("Bugle?")));
        assertEquals("bugle_shroom", ItemFields.slugOf(item("Bugle Shroom")));
        assertEquals("bugle_shroom_(poisonous)", ItemFields.slugOf(item("Bugle Shroom (Poisonous)")));
        // 撇号与括号在白名单里,原样保留
        assertEquals("pandora's_lunchbox", ItemFields.slugOf(item("Pandora's Lunchbox")));
    }

    @Test
    void slugIsCaseInsensitiveToInput() {
        assertEquals(ItemFields.slugOf(item("Hot Dog")), ItemFields.slugOf(item("HOT DOG")));
    }

    @Test
    void foodWinsOverEnemyTag() {
        // Scorpion 同时带 Food 与 Enemy,一级导航只出现一次,落点是食物（契约 §0.5）
        assertEquals("FOOD", ItemFields.primaryTypeOf(item("Scorpion", "Food", "Enemy")));
    }

    @Test
    void anyTypeContainingFoodIsFood() {
        assertEquals("FOOD", ItemFields.primaryTypeOf(item("Aloe Vera", "Food", "Natural food")));
        assertEquals("FOOD", ItemFields.primaryTypeOf(item("Coconut", "Food", "Packaged food")));
        // 数据源里含 Berry / Mushroom 的条目一律同时含 Food（契约 §0.4 已对库验证）
        assertEquals("FOOD", ItemFields.primaryTypeOf(item("Green Crispberry", "Food", "Berry", "Natural food")));
        // 不分大小写,前端从别处拿到小写值也不该落空
        assertEquals("FOOD", ItemFields.primaryTypeOf(item("Hot Dog", "food")));
    }

    @Test
    void nonFoodTypesFollowThePriorityChain() {
        assertEquals("CONSUMABLE", ItemFields.primaryTypeOf(item("Bandage", "Consumable")));
        assertEquals("CONSUMABLE", ItemFields.primaryTypeOf(item("Bandage", "Consumable", "Equipment")));
        assertEquals("EQUIPMENT", ItemFields.primaryTypeOf(item("Backpack", "Equipment", "Deployable")));
        assertEquals("DEPLOYABLE", ItemFields.primaryTypeOf(item("Tent", "Deployable", "Mystical item")));
        // 全库 5 条护身符都同时含 Mystical item,顺序上护身符在前
        assertEquals("AMULET", ItemFields.primaryTypeOf(item("Scout's Ambition", "Amulet", "Mystical item")));
        assertEquals("MYSTICAL", ItemFields.primaryTypeOf(item("Warp Compass", "Mystical item")));
        assertEquals("MISC", ItemFields.primaryTypeOf(item("Some Trash", "Misc")));
        // 纯敌人：只有 Enemy 才归 ENEMY
        assertEquals("ENEMY", ItemFields.primaryTypeOf(item("Mantis", "Enemy")));
    }

    @Test
    void onlyTypeDimensionCounts() {
        // biome / rarity / flag 维度里出现同名取值不参与归约
        Item item = item("Hot Dog", "Food");
        item.getTag().add(tag("biome", "Gloom"));
        item.getTag().add(tag("flag", "cookable"));
        item.getTag().add(tag("code-that-is-not-type", "Enemy"));
        assertEquals("FOOD", ItemFields.primaryTypeOf(item));
    }

    @Test
    void unknownTypeValueFallsBackToMisc() {
        // 与 TagDictionary 同一套容错口径：数据源新增取值不炸整批,归到杂物而不是抛异常
        assertEquals("MISC", ItemFields.primaryTypeOf(item("New Thing", "Something New")));
        assertEquals("MISC", ItemFields.primaryTypeOf(item("No Tag At All")));
    }

    @Test
    void primaryTypeOrderIsTheNavigationOrder() {
        assertEquals(List.of("FOOD", "CONSUMABLE", "EQUIPMENT", "DEPLOYABLE",
                             "AMULET", "MYSTICAL", "MISC", "ENEMY"),
                     ItemFields.PRIMARY_TYPE_ORDER);
    }

    /** 造一条只有英文名与 type 标签的条目：两个计算值的全部输入就是这两样 */
    private static Item item(String nameEn, String... typeValues) {
        Item item = new Item();
        item.setNameEn(nameEn);
        List<ItemTag> tags = new ArrayList<>();
        for (String value : typeValues) {
            tags.add(tag("type", value));
        }
        item.setTag(tags);
        return item;
    }

    private static ItemTag tag(String code, String value) {
        ItemTag tag = new ItemTag();
        tag.setCode(code);
        tag.setValue(value);
        return tag;
    }
}
