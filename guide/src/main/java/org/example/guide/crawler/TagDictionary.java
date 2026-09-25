package org.example.guide.crawler;

import java.util.Map;

/**
 * 标签中文字典，写死在采集代码里（取值来自 issue #11 的定稿清单）。
 *
 * 两条不变式:
 * 1. value 一律保留数据源原值,零转换 —— 图鉴里的每个标签都能直接对回数据源;
 * 2. 字典里查不到的取值**不丢弃** —— value 照存、nameZh 留空,由采集报告给出警告。
 *    这样数据源新增一个生态或稀有度时只是报告里多一条警告,不会把整批采集炸掉。
 *
 * 按维度分开写是刻意的,同一处地方可能在多个维度里各出现一次
 * (例如 Airport / Peak / Scout Statue 既是生态也可能是来源),中文名重复是有意的:
 * 字典的形状要跟定稿清单一一对得上,不做跨维度归并。
 */
public final class TagDictionary {

    /** tag.code 的六个维度词 */
    public static final String TYPE = "type";
    public static final String BIOME = "biome";
    public static final String RARITY = "rarity";
    public static final String SOURCE = "source";
    public static final String LOCATION = "location";
    public static final String FLAG = "flag";

    /** flag 维度的两个取值 */
    public static final String FLAG_COOKABLE = "cookable";
    public static final String FLAG_REMOVED = "removed";

    private static final Map<String, String> TYPE_ZH = Map.ofEntries(
            entry("Food", "食物"),
            entry("Natural food", "天然食物"),
            entry("Packaged food", "包装食品"),
            entry("Berry", "浆果"),
            entry("Mushroom", "蘑菇"),
            entry("Consumable", "消耗品"),
            entry("Equipment", "装备"),
            entry("Deployable", "可放置物"),
            entry("Mystical item", "神秘物品"),
            entry("Amulet", "护身符"),
            entry("Misc", "杂物"),
            entry("Enemy", "敌人"));

    private static final Map<String, String> BIOME_ZH = Map.ofEntries(
            entry("Tropics", "雨林"),
            entry("Roots", "森蕈"),
            entry("Mesa", "方山"),
            entry("Shore", "海岸"),
            entry("Gloom", "雾沼"),
            entry("Alpine", "雪山"),
            entry("Airport", "机场"),
            entry("Peak", "顶峰"),
            entry("Caldera", "火山"),
            entry("The Citadel", "城塞"),
            entry("The Kiln", "熔炉"));

    private static final Map<String, String> RARITY_ZH = Map.ofEntries(
            entry("Common", "普通"),
            entry("Uncommon", "优秀"),
            entry("Rare", "稀有"),
            entry("Epic", "史诗"),
            entry("Mythic", "神话"),
            entry("Legendary", "传说"),
            entry("Ridiculously Rare", "极其罕见"));

    private static final Map<String, String> SOURCE_ZH = Map.ofEntries(
            entry("Regular Luggage", "普通行李"),
            entry("Big Luggage", "大型行李"),
            entry("Ancient Luggage", "古代行李"),
            entry("Explorer's Luggage", "探险家行李"),
            entry("Ancient Statue", "古代雕像"),
            entry("Ancient Statues", "古代雕像"),
            entry("Clown Luggage", "小丑行李"),
            entry("Scout Statue", "童军雕像"),
            entry("Stone Scout", "石头童军"),
            entry("Crash Site", "坠机点"),
            entry("Campfires", "篝火"),
            entry("Airport", "机场"),
            entry("Luggage", "行李"),
            entry("Big Egg", "大蛋"),
            entry("Small Egg", "小蛋"),
            entry("Blue Berrynana", "蓝莓蕉"),
            entry("Brown Berrynana", "棕莓蕉"),
            entry("Pink Berrynana", "粉莓蕉"),
            entry("Yellow Berrynana", "黄莓蕉"));

    private static final Map<String, String> LOCATION_ZH = Map.ofEntries(
            entry("Crash Site", "坠机点"),
            entry("Tomb", "墓穴"),
            entry("Photobooth", "照相亭"),
            entry("Scout Statue", "童军雕像"),
            entry("Campfires", "篝火"),
            entry("On ground", "地面"),
            entry("In Luggage", "行李中"),
            entry("Peak", "顶峰"),
            entry("Stone Scout", "石头童军"));

    /**
     * location 原值里 Campfire 与 Campfires 指的是同一处,定稿要求合并成一个码。
     * 归一方向是 Campfires —— 与 SOURCE 字典里的写法保持一致,避免同一处地方出现两种码。
     */
    private static final Map<String, String> LOCATION_CODE_ALIASES = Map.of("Campfire", "Campfires");

    private static final Map<String, String> FLAG_ZH = Map.ofEntries(
            entry(FLAG_COOKABLE, "可烹饪"),
            entry(FLAG_REMOVED, "已移除"));

    private static final Map<String, Map<String, String>> DICTIONARIES = Map.of(
            TYPE, TYPE_ZH,
            BIOME, BIOME_ZH,
            RARITY, RARITY_ZH,
            SOURCE, SOURCE_ZH,
            LOCATION, LOCATION_ZH,
            FLAG, FLAG_ZH);

    private TagDictionary() {
    }

    /**
     * 按维度 + 原值查中文名。
     *
     * @return 中文名;字典里没有这个取值时返回 null,调用方据此留空 nameZh 并记一条警告
     */
    public static String lookupZh(String dimension, String value) {
        Map<String, String> dictionary = DICTIONARIES.get(dimension);
        return dictionary == null ? null : dictionary.get(value);
    }

    /** 把 location 的原始取值归一成最终落库的码(剥标记之后调用) */
    public static String canonicalLocationCode(String value) {
        return LOCATION_CODE_ALIASES.getOrDefault(value, value);
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return Map.entry(key, value);
    }
}
