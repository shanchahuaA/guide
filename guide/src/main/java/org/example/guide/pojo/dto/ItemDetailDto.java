package org.example.guide.pojo.dto;

import lombok.Data;
import org.example.guide.dictionary.EffectDictionary;
import org.example.guide.dictionary.TagDictionary;
import org.example.guide.pojo.Effect;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;
import org.example.guide.utils.ItemFields;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图鉴**详情**条目的接口响应结构（契约 §2）。
 *
 * <p>{@code data} 直接就是这个对象，不再包一层 {@code item} —— 组装见
 * {@code ItemServiceImpl.getDetailDto}，那里负责"先按 slug 找到条目、找不到给什么"。
 *
 * <p>这份 DTO 就是执行契约 §0.2 的地方：{@code tag} / {@code effect} 两个 JSON 列
 * **不原样透传**（小程序有版本碎片问题，改一个 JSON 内的 key 就白屏），
 * 拆成固定字段下发；但元素里的 {@code code} 保留数据源原值，中文名由服务端随元素一起给。
 *
 * <p>两个 JSON 列的拆法：
 * <ul>
 *   <li>{@code tag} → {@code tags}，六维全量，元素形状不变（前端一套代码认字典与条目两种来源）；</li>
 *   <li>{@code effect}（生熟混在一个数组里）→ {@code raw} / {@code cooked} 两个结构相同的数组，
 *       熟值靠 {@code _COOKED} 后缀区分（见 {@link EffectView#from}）。</li>
 * </ul>
 */
@Data
public class ItemDetailDto {

    private String slug;

    private String nameZh;

    private String nameEn;

    private String icon;

    private Float weight;

    private String primaryType;

    /** 取 {@code flag=cookable} 标签，**不重算**（采集时已算好）。只管生熟两栏数值的显隐 */
    private Boolean isCookable;

    /** 生食效果 */
    private List<EffectView> raw;

    /** 熟食效果；不可烹饪时是**空数组**，前端据此隐藏熟食那一栏 */
    private List<EffectView> cooked;

    /** 中文描述。英文 {@code description} **不下发**（契约 §2），所以这里没有对应字段 */
    private String descriptionZh;

    /** 空则 null */
    private String achievement;

    private List<ItemTag> tags;

    public static ItemDetailDto from(Item item) {
        ItemDetailDto dto = new ItemDetailDto();
        dto.setSlug(ItemFields.slugOf(item));
        dto.setNameZh(item.getNameZh());
        dto.setNameEn(item.getNameEn());
        dto.setIcon(item.getIcon());
        dto.setWeight(item.getWeight());
        dto.setPrimaryType(ItemFields.primaryTypeOf(item));
        dto.setIsCookable(isCookable(item));
        dto.setRaw(effectsOf(item, false));
        dto.setCooked(effectsOf(item, true));
        dto.setDescriptionZh(item.getDescriptionZh());
        dto.setAchievement(item.getAchievement());
        dto.setTags(item.getTag() == null ? List.of() : item.getTag());
        return dto;
    }

    /**
     * 契约 §2 的十二个字段，装进一个 {@code Map} 给 {@code BaseResult.data}。
     *
     * <p><b>为什么手写而不是 {@code objectMapper.convertValue}</b>：Spring Boot 4.1.1 的
     * {@code spring-boot-starter-jackson} 提供的是 Jackson 3（{@code tools.jackson}），
     * 而 MyBatis-Plus 把 Jackson 2 的 {@code jackson-databind} 声明成
     * {@code <optional>true</optional>} —— optional 不被下游继承，所以主源码的编译路径上
     * 根本没有 Jackson 2 的 {@code ObjectMapper}。补一份 Jackson 2 依赖（与 Jackson 3 并存）
     * 是另一个决定，不在本票范围内。契约 §8.2 那条"交给 Jackson、以后 DTO 加字段不会漏"
     * 的好处，在这里由 {@code ItemDetailDtoTest} 的字段面断言补上。
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：后者不允许 null 值，
     * 而 {@code achievement} 多半是 null（库里只有 28 条有）、{@code weight} 等也可能为空。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("slug", slug);
        map.put("nameZh", nameZh);
        map.put("nameEn", nameEn);
        map.put("icon", icon);
        map.put("weight", weight);
        map.put("primaryType", primaryType);
        map.put("isCookable", isCookable);
        map.put("raw", effectsToMaps(raw));
        map.put("cooked", effectsToMaps(cooked));
        map.put("descriptionZh", descriptionZh);
        map.put("achievement", achievement);
        map.put("tags", tagsToMaps(tags));
        return map;
    }

    private static List<Map<String, Object>> effectsToMaps(List<EffectView> views) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (EffectView view : views) {
            maps.add(view.toMap());
        }
        return maps;
    }

    /** tags 的元素形状：code / value / nameZh 三个字段，与 /api/tags 的元素完全一致（契约 §4） */
    private static List<Map<String, Object>> tagsToMaps(List<ItemTag> tags) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (ItemTag tag : tags) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", tag.getCode());
            map.put("value", tag.getValue());
            map.put("nameZh", tag.getNameZh());
            maps.add(map);
        }
        return maps;
    }

    /**
     * 可烹饪判据：采集时写好的 {@code flag=cookable} 标签。
     *
     * <p>**不重算**（契约 §0.4）——重算会让"接口返回的旗标"与"库里那条标签"成为两个可能分叉的事实。
     * 它管的只是生熟两栏数值的显隐，不是"游戏里能不能烤"（见 CONTEXT.md 可烹饪节）。
     */
    private static boolean isCookable(Item item) {
        if (item.getTag() == null) {
            return false;
        }
        return item.getTag().stream()
                .anyMatch(tag -> TagDictionary.FLAG.equals(tag.getCode())
                        && TagDictionary.FLAG_COOKABLE.equals(tag.getValue()));
    }

    /**
     * 从生熟混在一起的 {@code effect} 列里挑出要的那一半。
     *
     * <p>{@code item.getEffect()} 可能整列为 null（JSON 列映射失败或条目本身没有效果），
     * 退化成空数组而不是 NPE —— 与列表那边"空字段不炸接口"同一口径。
     */
    private static List<EffectView> effectsOf(Item item, boolean cooked) {
        if (item.getEffect() == null) {
            return List.of();
        }
        List<EffectView> views = new ArrayList<>();
        for (Effect effect : item.getEffect()) {
            EffectView view = EffectView.from(effect);
            if (view != null && view.isCookedValue() == cooked) {
                views.add(view);
            }
        }
        return views;
    }

    /**
     * 生熟两栏里的一行，五个字段（契约 §2.1）。
     *
     * <p>这个类**不是**被序列化出去的形状 —— 响应里它是 {@link #toMap()} 交出来的
     * {@code Map}。理由见外层 {@link ItemDetailDto#toMap()}：本仓库主源码路径上没有 Jackson 2 的
     * {@code ObjectMapper}，装配这一层只能手写。
     */
    @Data
    public static class EffectView {

        private static final String COOKED_SUFFIX = "_COOKED";

        /** **已剥掉 {@code _COOKED} 后缀**的数据源原值，如 HUNGER */
        private String code;

        /** 中文名，字典查不到时为空（与 TagDictionary 同一套容错口径） */
        private String nameZh;

        /** 带符号，负值表示消除/减少该状态。前端原样显示，不翻符号 */
        private Float value;

        private Float duration;

        private Float startDelay;

        /**
         * 这一行来自 {@code *_COOKED}、还是生值。
         *
         * <p>**只是拆分时的中间量，不进响应** —— 下发的恰好是契约那五个字段。
         * 多给一个"是不是熟的"既冗余，又可能与它所在的 {@code raw} / {@code cooked} 自相矛盾。
         * 字段名是 {@code cookedValue} 而不是 {@code isCooked}，Lombok 不会为它生成
         * {@code isCooked()} getter，因此即使有人误把它序列化也不会漏出去。
         */
        private boolean cookedValue;

        /** null 入参返回 null：effect 数组里不该有空元素，但真出现了也不该把接口带崩 */
        static EffectView from(Effect effect) {
            if (effect == null) {
                return null;
            }
            EffectView view = new EffectView();
            view.cookedValue = effect.getCode() != null && effect.getCode().endsWith(COOKED_SUFFIX);
            view.code = view.cookedValue
                    ? effect.getCode().substring(0, effect.getCode().length() - COOKED_SUFFIX.length())
                    : effect.getCode();
            view.nameZh = EffectDictionary.lookupZh(effect.getCode());
            view.value = effect.getValue();
            view.duration = effect.getDuration();
            view.startDelay = effect.getStartDelay();
            return view;
        }

        /**
         * 契约 §2.1 的五个字段。
         *
         * <p>{@code duration} / {@code startDelay} 可空**也照样下发 null**（键必须在）：
         * 前端读的是字段面，少一个键比值为 null 更难排查。
         */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", code);
            map.put("nameZh", nameZh);
            map.put("value", value);
            map.put("duration", duration);
            map.put("startDelay", startDelay);
            return map;
        }
    }
}
