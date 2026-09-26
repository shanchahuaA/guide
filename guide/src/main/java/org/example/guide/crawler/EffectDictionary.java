package org.example.guide.crawler;

import java.util.Map;
import java.util.Set;

/**
 * 状态效果的中文字典。
 *
 * <p>与 {@link TagDictionary} 并列、同一套口径：字典查不到的 code **不丢弃** ——
 * value 照存、{@code nameZh} 留空，由调用方决定要不要记一条警告，而不是把整个响应炸掉。
 * 数据源新增一个状态时只是响应里多一个没中文名的元素，不是 500。
 *
 * <p>词表照小程序详情页现有的 {@code EFFECT_ZH}（{@code guide-mini/pages/detail/detail.js}），
 * 出处在 {@code docs/local} 的官方译名。中文名归后端带之后，前端那份占位表整删。
 *
 * <p><b>只收 11 个基础 code。</b> 采集侧写出的熟食变体（{@code *_COOKED}）不单独收录 ——
 * 熟值存的是总量、不是另一种状态，对外剥掉后缀后查的就是同一张表（{@link #COOKED_SUFFIX}）。
 * 已知库里出现过的熟食变体只有
 * {@code HUNGER_COOKED} / {@code BONUS_COOKED} / {@code POISON_COOKED} /
 * {@code SPORES_COOKED} / {@code THORNS_COOKED} 五个，全部有对应的基础 code。
 */
public final class EffectDictionary {

    /**
     * 熟食变体的后缀。采集侧用它区分生值与熟值（见 {@code CookedEffects}）。
     *
     * <p>默认 private：剥后缀是"响应组装"的活，已经在 {@code pojo/dto/ItemDetailDto} 里做了一次，
     * 不该再从字典这边开一个口子让第二处去剥。
     */
    private static final String COOKED_SUFFIX = "_COOKED";

    private static final Map<String, String> EFFECT_ZH = Map.ofEntries(
            Map.entry("USES", "使用次数"),
            Map.entry("HUNGER", "饥饿"),
            Map.entry("BONUS", "额外耐力"),
            Map.entry("HEAT", "热量"),
            Map.entry("COLD", "寒冷"),
            Map.entry("POISON", "中毒"),
            Map.entry("SPORES", "孢子"),
            Map.entry("INJURY", "伤害"),
            Map.entry("DROWSY", "困倦"),
            Map.entry("THORNS", "荆棘"),
            Map.entry("CURSE", "诅咒"));

    /** 基础 code 的封闭集合。字典本身是 private 的，测试与采集报告用它核对覆盖 */
    public static final Set<String> BASE_CODES = EFFECT_ZH.keySet();

    private EffectDictionary() {
    }

    /**
     * 状态 effect 的 code → 中文名。
     *
     * @param code 数据源原值，如 {@code HUNGER} / {@code HUNGER_COOKED}；
     *             {@code _COOKED} 后缀在这里剥掉，所以生熟两种码查的是同一张表
     * @return 中文名；字典里没有这个 code 时返回 {@code null}，调用方据此留空 {@code nameZh}
     */
    public static String lookupZh(String code) {
        if (code == null) {
            return null;
        }
        String base = code.endsWith(COOKED_SUFFIX)
                ? code.substring(0, code.length() - COOKED_SUFFIX.length())
                : code;
        return EFFECT_ZH.get(base);
    }
}
