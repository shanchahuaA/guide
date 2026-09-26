package org.example.guide.pojo.dto;

import org.example.guide.pojo.dto.ItemDetailDto.EffectView;
import org.example.guide.pojo.Effect;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 详情 DTO 的两件硬事：**字段面是契约**（多一个老版本小程序不白屏、少一个会），
 * 以及**生熟拆分**（契约 §2.1，详情页最主要的用途就是生熟对比）。
 *
 * <p>这里断言的是 {@link ItemDetailDto#toMap()} —— 响应里 {@code data} 就是它交出来的形状，
 * 所以字段面必须在这条路径上钉住，而不是在某个仅供序列化的中间对象上。
 *
 * <p>纯单元测试，不加载 Spring 上下文 —— 拆分与查字典都是纯函数，跑测试不该需要 MySQL。
 */
class ItemDetailDtoTest {

    @Test
    void exposesExactlyTheTwelveContractFields() {
        assertEquals(Set.of("slug", "nameZh", "nameEn", "icon", "weight", "primaryType", "isCookable",
                        "raw", "cooked", "descriptionZh", "achievement", "tags"),
                ItemDetailDto.from(hotDog()).toMap().keySet());
    }

    @Test
    void EnglishDescriptionIsNotShipped() {
        Map<String, Object> json = ItemDetailDto.from(hotDog()).toMap();
        // 英文 description 最长 1262 字，是列表中英描述都剔除的那条口径的同一理由
        assertTrue(!json.containsKey("description"), "响应里不该出现英文 description");
        assertTrue(!json.containsKey("id"), "详情靠 slug 取，id 是库内自增、换机器重灌就变");
    }

    /** 契约 §2.1：raw / cooked 的元素恰好五个字段，中间量 cookedValue 不允许漏出来 */
    @Test
    void effectElementsExposeExactlyFiveFields() {
        Map<String, Object> json = ItemDetailDto.from(hotDog()).toMap();
        for (Object element : asList(json.get("raw"))) {
            assertEquals(Set.of("code", "nameZh", "value", "duration", "startDelay"),
                    asMap(element).keySet());
        }
        for (Object element : asList(json.get("cooked"))) {
            assertEquals(Set.of("code", "nameZh", "value", "duration", "startDelay"),
                    asMap(element).keySet());
        }
    }

    /** tags 的元素形状与 /api/tags 一致，恰好三个字段（契约 §4） */
    @Test
    void tagElementsExposeExactlyThreeFields() {
        Map<String, Object> json = ItemDetailDto.from(hotDog()).toMap();
        for (Object element : asList(json.get("tags"))) {
            assertEquals(Set.of("code", "value", "nameZh"), asMap(element).keySet());
        }
    }

    /** Hot Dog 的已知答案（契约 §2 样例，已对库核对） */
    @Test
    void splitsHotDogIntoRawAndCooked() {
        ItemDetailDto dto = ItemDetailDto.from(hotDog());

        assertEquals(List.of("HUNGER", "BONUS", "USES"), codes(dto.getRaw()));
        assertEquals(List.of(-30f, 20f, 1f), values(dto.getRaw()));
        assertEquals(List.of("饥饿", "额外耐力", "使用次数"), names(dto.getRaw()));

        assertEquals(List.of("HUNGER", "BONUS"), codes(dto.getCooked()));
        // 熟值存的是**总量**（-60 而不是增量的 -30），服务端不再算一次
        assertEquals(List.of(-60f, 30f), values(dto.getCooked()));

        assertTrue(dto.getIsCookable());
    }

    @Test
    void cookedCodeHasTheSuffixStripped() {
        ItemDetailDto dto = ItemDetailDto.from(hotDog());
        assertEquals(List.of("HUNGER", "BONUS"), codes(dto.getCooked()));
        for (EffectView view : dto.getCooked()) {
            assertTrue(!view.getCode().endsWith("_COOKED"), "对外的 code 必须已剥掉 _COOKED 后缀");
        }
    }

    /** 不可烹饪：cooked 是**空数组**、isCookable=false，前端据此隐藏熟食那一栏（契约 §2.1） */
    @Test
    void notCookableYieldsEmptyCookedArrayAndFalseFlag() {
        ItemDetailDto dto = ItemDetailDto.from(firstAidKit());

        assertEquals(List.of("INJURY", "POISON", "SPORES", "USES"), codes(dto.getRaw()));
        assertEquals(List.of("伤害", "中毒", "孢子", "使用次数"), names(dto.getRaw()));
        assertTrue(dto.getCooked().isEmpty(), "不可烹饪时 cooked 必须是空数组，不是 null");
        assertTrue(!dto.getIsCookable());
        // 空数组在响应里也得是 []，不是消失或 null
        assertTrue(asList(dto.toMap().get("cooked")).isEmpty());
    }

    /** isCookable 取 flag=cookable 标签，不重算 —— 只看标签，不看 effect 里有没有熟值 */
    @Test
    void isCookableComesFromTheTagNotFromTheEffects() {
        Item withFlagButNoCookedEffects = hotDog();
        withFlagButNoCookedEffects.setEffect(rawOnly());
        assertTrue(ItemDetailDto.from(withFlagButNoCookedEffects).getIsCookable());
        assertTrue(ItemDetailDto.from(withFlagButNoCookedEffects).getCooked().isEmpty());

        Item withCookedEffectsButNoFlag = hotDog();
        withCookedEffectsButNoFlag.setTag(List.of(typeTag("Food", "食物")));
        assertTrue(!ItemDetailDto.from(withCookedEffectsButNoFlag).getIsCookable());
        assertEquals(List.of("HUNGER", "BONUS"), codes(ItemDetailDto.from(withCookedEffectsButNoFlag).getCooked()));
    }

    /** tags 六个维度全量下发（契约 §2.2，详情页的生态 / 来源 / 稀有度展示区要用） */
    @Test
    void shipsAllSixTagDimensions() {
        ItemDetailDto dto = ItemDetailDto.from(hotDog());
        Set<String> dimensions = new HashSet<>();
        for (ItemTag tag : dto.getTags()) {
            dimensions.add(tag.getCode());
        }
        assertEquals(Set.of("type", "biome", "rarity", "source", "location", "flag"), dimensions);
        // 中文名服务端已经填好，原样带出去
        assertEquals("雾沼", dto.getTags().stream()
                .filter(t -> "biome".equals(t.getCode())).findFirst().orElseThrow().getNameZh());
    }

    /** achievement 库里只有 28 条有，没有时是 null（契约 §2.2），且这个 null 要真的进 Map */
    @Test
    void achievementIsNullWhenAbsent() {
        assertNull(ItemDetailDto.from(hotDog()).getAchievement());
        assertTrue(ItemDetailDto.from(hotDog()).toMap().containsKey("achievement"),
                "achievement 可空，但键必须在响应里");
        assertNull(ItemDetailDto.from(hotDog()).toMap().get("achievement"));
        assertEquals("大胃王奖章", ItemDetailDto.from(withAchievement()).toMap().get("achievement"));
    }

    @Test
    void descriptionZhIsTheOnlyDescriptionShipped() {
        assertEquals("热狗肠是 PEAK 中的天然食物。", ItemDetailDto.from(hotDog()).getDescriptionZh());
        assertEquals("热狗肠是 PEAK 中的天然食物。", ItemDetailDto.from(hotDog()).toMap().get("descriptionZh"));
    }

    /** 字典查不到的 code 照存、nameZh 留空 —— 与 TagDictionary 同一套容错口径，不炸整批 */
    @Test
    void unknownEffectCodeIsKeptWithBlankName() {
        Item item = hotDog();
        List<Effect> effects = new ArrayList<>(rawOnly());
        effects.add(effect("BRAND_NEW_EFFECT", 5f, null, null));
        item.setEffect(effects);

        ItemDetailDto dto = ItemDetailDto.from(item);
        assertTrue(codes(dto.getRaw()).contains("BRAND_NEW_EFFECT"));
        EffectView unknown = dto.getRaw().stream()
                .filter(v -> "BRAND_NEW_EFFECT".equals(v.getCode())).findFirst().orElseThrow();
        assertNull(unknown.getNameZh());
    }

    /** effect 是 JSON 列，可能整列为 null（映射失败或该条目没有效果）—— 两个数组都退化成空，不该 NPE */
    @Test
    void nullEffectColumnDegradesToEmptyArrays() {
        Item item = hotDog();
        item.setEffect(null);
        ItemDetailDto dto = ItemDetailDto.from(item);
        assertTrue(dto.getRaw().isEmpty());
        assertTrue(dto.getCooked().isEmpty());
        assertTrue(asList(dto.toMap().get("raw")).isEmpty());
        assertTrue(asList(dto.toMap().get("cooked")).isEmpty());
    }

    @Test
    void nullTagColumnDegradesToEmptyTags() {
        Item item = hotDog();
        item.setTag(null);
        assertTrue(ItemDetailDto.from(item).getTags().isEmpty());
        assertTrue(asList(ItemDetailDto.from(item).toMap().get("tags")).isEmpty());
    }

    // ── 取 Map 里的值（不引 Jackson：主源码路径上没有 Jackson 2，测试就一起不用） ──────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static List<String> codes(List<EffectView> views) {
        List<String> codes = new ArrayList<>();
        for (EffectView view : views) {
            codes.add(view.getCode());
        }
        return codes;
    }

    private static List<Float> values(List<EffectView> views) {
        List<Float> values = new ArrayList<>();
        for (EffectView view : views) {
            values.add(view.getValue());
        }
        return values;
    }

    private static List<String> names(List<EffectView> views) {
        List<String> names = new ArrayList<>();
        for (EffectView view : views) {
            names.add(view.getNameZh());
        }
        return names;
    }

    /** 库里 Hot Dog 那一行（生熟数值已对库核对） */
    private static Item hotDog() {
        Item item = new Item();
        item.setId(1L);
        item.setNameEn("Hot Dog");
        item.setNameZh("热狗肠");
        item.setIcon("/icons/Hot_Dog.png");
        item.setWeight(2.5F);
        item.setDescription("Hot Dog is a natural food...");
        item.setDescriptionZh("热狗肠是 PEAK 中的天然食物。");
        item.setEffect(hotDogEffects());

        List<ItemTag> tags = new ArrayList<>();
        tags.add(typeTag("Food", "食物"));
        tags.add(tag("biome", "Gloom", "雾沼"));
        tags.add(tag("rarity", "Legendary", "传说"));
        tags.add(tag("source", "Big Luggage", "大型行李"));
        tags.add(tag("location", "On ground", "地面"));
        tags.add(tag("flag", "cookable", "可烹饪"));
        item.setTag(tags);
        return item;
    }

    private static List<Effect> hotDogEffects() {
        List<Effect> effects = new ArrayList<>(rawOnly());
        effects.add(effect("HUNGER_COOKED", -60f, null, null));
        effects.add(effect("BONUS_COOKED", 30f, null, null));
        return effects;
    }

    private static List<Effect> rawOnly() {
        List<Effect> effects = new ArrayList<>();
        effects.add(effect("HUNGER", -30f, null, null));
        effects.add(effect("BONUS", 20f, null, null));
        effects.add(effect("USES", 1f, null, null));
        return effects;
    }

    /** 库里 First Aid Kit 那一行：写着 No effect.，不是食物，没有 flag=cookable */
    private static Item firstAidKit() {
        Item item = new Item();
        item.setNameEn("First Aid Kit");
        item.setNameZh("急救箱");
        item.setWeight(5.0F);
        item.setDescriptionZh("急救箱是 PEAK 中的消耗品。");
        List<Effect> effects = new ArrayList<>();
        effects.add(effect("INJURY", -100f, null, null));
        effects.add(effect("POISON", -100f, null, null));
        effects.add(effect("SPORES", -100f, null, null));
        effects.add(effect("USES", 1f, null, null));
        item.setEffect(effects);
        item.setTag(List.of(typeTag("Consumable", "消耗品")));
        return item;
    }

    private static Item withAchievement() {
        Item item = hotDog();
        item.setAchievement("大胃王奖章");
        return item;
    }

    private static Effect effect(String code, Float value, Float duration, Float startDelay) {
        Effect effect = new Effect();
        effect.setCode(code);
        effect.setValue(value);
        effect.setDuration(duration);
        effect.setStartDelay(startDelay);
        return effect;
    }

    private static ItemTag typeTag(String value, String nameZh) {
        return tag("type", value, nameZh);
    }

    private static ItemTag tag(String code, String value, String nameZh) {
        ItemTag tag = new ItemTag();
        tag.setCode(code);
        tag.setValue(value);
        tag.setNameZh(nameZh);
        return tag;
    }
}
