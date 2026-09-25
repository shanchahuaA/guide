package org.example.guide.crawler;

import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源 Cargo 表 Items 的一行。
 *
 * 在 HTTP 边界上立刻收口成强类型:下游拿到的是 String / Float / boolean,不再面对 Map,
 * 字段名写错在采集阶段就会暴露,而不是等到落库或前端渲染。
 *
 * 字段清单对应 issue #11 的定稿范围:本票只用到生食侧字段,wikitext、图标等字段不在这里。
 */
@Getter
public class CargoItemRow {

    /** 数据源页面名(Cargo 的 _pageName),失败明细里用它定位是哪一行 */
    private String page;

    /** 展示名,就是图鉴条目的英文名;3 个毒蘑菇变体靠它与普通版区分 */
    private String display;

    /**
     * 多值字段:数据源底层是逗号分隔的字符串(如 "Food, Natural food, Berry")。
     * 这里保持原样的字符串,拆开在转换时做 —— 用 SQL 精确匹配这种字段会失效(见 CONTEXT.md)。
     */
    private String type;
    private String biome;
    private String location;
    private String source;

    /** 稀有度,单值 */
    private String rarity;

    /** 使用次数 */
    private Float uses;

    /** 重量,实测含负值与一位小数,所以用 Float 而不是整型 */
    private Float weight;

    /** 十个生食状态效果 */
    private Float hunger;
    private Float bonus;
    private Float heat;
    private Float cold;
    private Float injury;
    private Float poison;
    private Float spores;
    private Float drowsy;
    private Float curse;
    private Float thorns;

    /** 状态效果的附属值 */
    private Float coldTime;
    private Float poisonTime;
    private Float poisonStart;

    /** 数据源标记的"已从游戏移除" */
    private boolean removed;

    private CargoItemRow() {
    }

    public static CargoItemRow fromMap(Map<String, Object> raw) {
        Map<String, Object> m = normalizeKeys(raw);
        CargoItemRow row = new CargoItemRow();
        row.page = text(m, "page");
        row.display = text(m, "display");
        row.type = multiValue(m, "type");
        row.biome = multiValue(m, "biome");
        row.location = multiValue(m, "location");
        row.source = multiValue(m, "source");
        row.rarity = text(m, "rarity");
        row.uses = number(m, "uses");
        row.weight = number(m, "weight");
        row.hunger = number(m, "hunger");
        row.bonus = number(m, "bonus");
        row.heat = number(m, "heat");
        row.cold = number(m, "cold");
        row.injury = number(m, "injury");
        row.poison = number(m, "poison");
        row.spores = number(m, "spores");
        row.drowsy = number(m, "drowsy");
        row.curse = number(m, "curse");
        row.thorns = number(m, "thorns");
        row.coldTime = number(m, "coldtime");
        row.poisonTime = number(m, "poisontime");
        row.poisonStart = number(m, "poisonstart");
        row.removed = bool(m, "removed");
        return row;
    }

    /** 数据源对响应里的 key 不保证大小写与请求一致,统一按小写取,避免一个字母对不上就整列为空 */
    private static Map<String, Object> normalizeKeys(Map<String, Object> raw) {
        Map<String, Object> m = new HashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> {
                if (key != null) {
                    m.put(key.toLowerCase(Locale.ROOT), value);
                }
            });
        }
        return m;
    }

    private static Object value(Map<String, Object> m, String key) {
        return m.get(key.toLowerCase(Locale.ROOT));
    }

    /** 空值有两种形态:字段整个不出现,或者出现了但是空串。两者等价 */
    private static String text(Map<String, Object> m, String key) {
        Object v = value(m, key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 多值字段:正常是逗号分隔的字符串;某些 Cargo 版本会直接给数组,一并接住 */
    private static String multiValue(Map<String, Object> m, String key) {
        Object v = value(m, key);
        if (v instanceof List<?> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        return text(m, key);
    }

    /**
     * 数值:空 → null。
     * 非空但解析不出数字 → 抛异常,让这一条进失败明细,而不是把脏值静默存进库。
     */
    private static Float number(Map<String, Object> m, String key) {
        Object v = value(m, key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number number) {
            return number.floatValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Float.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("字段 " + key + " 的取值不是数字:" + s, e);
        }
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = value(m, key);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        // 数据源的 Boolean 列在 JSON 里可能是 true/false,也可能是 1/0
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
