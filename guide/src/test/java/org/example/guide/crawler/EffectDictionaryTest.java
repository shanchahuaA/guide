package org.example.guide.crawler;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 效果字典的口径回归（契约 §8.1）。
 *
 * <p>不加载 Spring 上下文：查表是纯函数。
 *
 * <p>字典是**封闭集合**（CONTEXT.md 状态效果节），所以这里把 11 个基础 code 钉死 ——
 * 数据源新增状态时该看见的是"响应的 nameZh 空了"，不是静默多出一个没中文名的效果。
 */
class EffectDictionaryTest {

    @Test
    void coversElevenBaseCode() {
        assertEquals(Set.of("USES", "HUNGER", "BONUS", "HEAT", "COLD", "POISON",
                        "SPORES", "INJURY", "DROWSY", "THORNS", "CURSE"),
                EffectDictionary.BASE_CODES);
    }

    @Test
    void chineseNamesMatchTheAppDictionary() {
        // 词表照 guide-mini/pages/detail/detail.js 现有的 EFFECT_ZH，改这里要同步改前端（或前端整删）
        assertEquals("饥饿", EffectDictionary.lookupZh("HUNGER"));
        assertEquals("额外耐力", EffectDictionary.lookupZh("BONUS"));
        assertEquals("热量", EffectDictionary.lookupZh("HEAT"));
        assertEquals("寒冷", EffectDictionary.lookupZh("COLD"));
        assertEquals("伤害", EffectDictionary.lookupZh("INJURY"));
        assertEquals("中毒", EffectDictionary.lookupZh("POISON"));
        assertEquals("孢子", EffectDictionary.lookupZh("SPORES"));
        assertEquals("困倦", EffectDictionary.lookupZh("DROWSY"));
        assertEquals("诅咒", EffectDictionary.lookupZh("CURSE"));
        assertEquals("荆棘", EffectDictionary.lookupZh("THORNS"));
        assertEquals("使用次数", EffectDictionary.lookupZh("USES"));
    }

    /** 熟食变体不单独收录，剥掉后缀查的是同一张表 */
    @Test
    void cookedVariantResolvesToTheSameEntry() {
        for (String base : EffectDictionary.BASE_CODES) {
            assertEquals(EffectDictionary.lookupZh(base), EffectDictionary.lookupZh(base + "_COOKED"),
                    base + "_COOKED 应查回 " + base + " 的中文名");
        }
    }

    /** 只有**结尾**的后缀算熟食标记，名字中间的 _COOKED 不该被拦腰切掉 */
    @Test
    void onlyTheTrailingSuffixIsStripped() {
        assertNull(EffectDictionary.lookupZh("_COOKED"));
        assertNull(EffectDictionary.lookupZh("COOKED_HUNGER"));
        assertNull(EffectDictionary.lookupZh(null));
    }

    /** 未知 code 照存、nameZh 留空 —— 与 TagDictionary 同一套容错口径，不炸整批 */
    @Test
    void unknownCodeReturnsNull() {
        assertNull(EffectDictionary.lookupZh("BRAND_NEW_EFFECT"));
        assertNull(EffectDictionary.lookupZh(""));
        assertTrue(EffectDictionary.BASE_CODES.stream().noneMatch("BRAND_NEW_EFFECT"::equals));
    }
}
