package org.example.guide.crawler;

import org.example.guide.pojo.Effect;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源行 → 图鉴条目。
 *
 * 转换分两级:
 * <ul>
 *   <li>{@link #convert(CargoItemRow)} —— 只用数据源**结构化行**就能定的那一半:
 *       五维标签(type / biome / rarity / source / location)、生食状态效果、USES、
 *       flag=removed 旗标。图标下载(见 #16)也挂在这一级之后;</li>
 *   <li>{@link #convert(CargoItemRow, WikitextParams)} —— 再加上**页面源文**里才有的那一半
 *       (熟食覆盖值、可烹饪判据,见 #14)。源文取不到时传
 *       {@link WikitextParams#EMPTY},熟食值退回模板公式算,采集不断。</li>
 * </ul>
 *
 * 描述与成就(#15)同样出自页面源文,但不在本票范围内 —— 本票产出的条目
 * description / achievement 是空的,这是预期状态。
 *
 * 图标也不在这里填:它要先下载成功了才有值,由采集服务在转换之后补上(见 ItemIconDownloader)。
 */
@Component
public class ItemConverter {

    /**
     * 状态效果代码。状态在 CONTEXT.md 里是**封闭集合**(十个状态 + 使用次数),
     * 所以代码写死在这里;熟食后缀码(*_COOKED)按同样的写法落在 CookedEffects 里。
     */
    private static final String HUNGER = "HUNGER";
    private static final String BONUS = "BONUS";
    private static final String HEAT = "HEAT";
    private static final String COLD = "COLD";
    private static final String INJURY = "INJURY";
    private static final String POISON = "POISON";
    private static final String SPORES = "SPORES";
    private static final String DROWSY = "DROWSY";
    private static final String CURSE = "CURSE";
    private static final String THORNS = "THORNS";
    private static final String USES = "USES";

    private final CookedEffects cookedEffects;

    public ItemConverter(CookedEffects cookedEffects) {
        this.cookedEffects = cookedEffects;
    }

    /**
     * 只靠结构化行转换,熟食值一律按模板公式算(不看页面源文)。
     * 源文那一趟整体不可得时的兜底路径,离线核对单行数据时也用这个入口。
     *
     * @throws IllegalArgumentException 该行连英文名都没有时抛出,由上层记进失败明细
     */
    public ConvertedItem convert(CargoItemRow row) {
        return convert(row, WikitextParams.EMPTY);
    }

    /**
     * 转换一行数据(含页面源文里的熟食覆盖值)。
     *
     * @throws IllegalArgumentException 该行连英文名都没有时抛出,由上层记进失败明细
     */
    public ConvertedItem convert(CargoItemRow row, WikitextParams params) {
        // 英文名取数据源的 display:3 个毒蘑菇变体在数据源里本来就是独立行、display 与普通版不同,
        // 靠它天然独立成条,不需要额外解析
        String nameEn = row.getDisplay();
        if (nameEn == null || nameEn.isBlank()) {
            // 没有名字就没法按 nameEn upsert:硬塞进去只会在库里留下查不到的垃圾行,
            // 而且每次采集都会再插一遍,破坏"重复触发不产生重复行"这条不变式
            throw new IllegalArgumentException("数据源该行没有 display(英文名),无法作为图鉴条目");
        }

        List<String> unknownDictionaryValues = new ArrayList<>();
        List<String> typeValues = splitMultiValue(row.getType());

        // 熟食效果只算一次,标签侧(可烹饪判据)与效果侧共用同一份结果 ——
        // 两处都调一次 build 会让"什么算熟食值"这件事有两个需要同步的地方
        List<Effect> cookedEffectsOfItem = cookedEffects.build(row, params, typeValues);

        Item item = new Item();
        item.setNameEn(nameEn);
        item.setWeight(row.getWeight());
        // 中文名一律留空,由后续的翻译对照表工作流填充
        item.setNameZh(null);
        item.setTag(buildTags(row, typeValues, cookedEffectsOfItem, unknownDictionaryValues));
        item.setEffect(buildEffects(row, cookedEffectsOfItem));

        return new ConvertedItem(item, unknownDictionaryValues);
    }

    private List<ItemTag> buildTags(CargoItemRow row, List<String> typeValues, List<Effect> cookedEffectsOfItem,
                                    List<String> unknownDictionaryValues) {
        List<ItemTag> tags = new ArrayList<>();

        for (String value : typeValues) {
            addTag(tags, TagDictionary.TYPE, value, unknownDictionaryValues);
        }
        for (String value : splitMultiValue(row.getBiome())) {
            addTag(tags, TagDictionary.BIOME, value, unknownDictionaryValues);
        }
        addTag(tags, TagDictionary.RARITY, row.getRarity(), unknownDictionaryValues);
        for (String value : splitMultiValue(row.getSource())) {
            addTag(tags, TagDictionary.SOURCE, value, unknownDictionaryValues);
        }
        for (String raw : splitMultiValue(row.getLocation())) {
            // 原值是 wikitext(如 [[Crash Site]]),先剥标记;Campfire 与 Campfires 合并成同一个码
            String value = TagDictionary.canonicalLocationCode(WikitextUtil.stripMarkup(raw));
            addTag(tags, TagDictionary.LOCATION, value, unknownDictionaryValues);
        }
        if (row.isRemoved()) {
            // 已移除条目照常入库,列表不过滤 —— 图鉴收录游戏里已经看不到的东西,这是明确的产品决定
            addTag(tags, TagDictionary.FLAG, TagDictionary.FLAG_REMOVED, unknownDictionaryValues);
        }
        // flag=cookable:存在非零熟食数值(见 CONTEXT.md)。
        // 显式写的 0("煮掉了"这类有效信息)不算 —— 只写了 0 的条目仍不带这个旗标。
        if (CookedEffects.containsNonZero(cookedEffectsOfItem)) {
            addTag(tags, TagDictionary.FLAG, TagDictionary.FLAG_COOKABLE, unknownDictionaryValues);
        }

        return tags;
    }

    /** 加一条标签;字典里查不到取值时 value 照存、nameZh 留空,并记下待报告 */
    private void addTag(List<ItemTag> tags, String dimension, String value, List<String> unknownDictionaryValues) {
        if (value == null || value.isBlank()) {
            return;
        }
        ItemTag tag = new ItemTag();
        tag.setCode(dimension);
        tag.setValue(value);

        String nameZh = TagDictionary.lookupZh(dimension, value);
        if (nameZh == null) {
            unknownDictionaryValues.add(dimension + "=" + value);
        } else {
            tag.setNameZh(nameZh);
        }

        tags.add(tag);
    }

    private List<Effect> buildEffects(CargoItemRow row, List<Effect> cookedEffectsOfItem) {
        List<Effect> effects = new ArrayList<>();

        // 生食状态效果:数值原样落库,负号语义是"消除/减少该状态"(见 CONTEXT.md)
        addEffect(effects, HUNGER, row.getHunger(), null, null);
        addEffect(effects, BONUS, row.getBonus(), null, null);
        addEffect(effects, HEAT, row.getHeat(), null, null);
        addEffect(effects, COLD, row.getCold(), row.getColdTime(), null);
        addEffect(effects, INJURY, row.getInjury(), null, null);
        addEffect(effects, POISON, row.getPoison(), row.getPoisonTime(), row.getPoisonStart());
        addEffect(effects, SPORES, row.getSpores(), null, null);
        addEffect(effects, DROWSY, row.getDrowsy(), null, null);
        addEffect(effects, CURSE, row.getCurse(), null, null);
        addEffect(effects, THORNS, row.getThorns(), null, null);

        // USES 是使用次数;数据源里 uses=0 表示这个物品没有使用次数,不写
        if (row.getUses() != null && row.getUses() != 0f) {
            addEffect(effects, USES, row.getUses(), null, null);
        }

        // 熟食效果(总量口径)同一批跟进来,生熟在库里是同一个 effect 数组里的两种码
        effects.addAll(cookedEffectsOfItem);

        return effects;
    }

    /** 值为空则整条效果都不进数组("空字段不进数组") */
    private void addEffect(List<Effect> effects, String code, Float value, Float duration, Float startDelay) {
        if (value == null) {
            return;
        }
        Effect effect = new Effect();
        effect.setCode(code);
        effect.setValue(value);
        effect.setDuration(duration);
        effect.setStartDelay(startDelay);
        effects.add(effect);
    }

    /**
     * 拆多值字段:数据源底层是逗号分隔的字符串(如 "Food, Natural food, Berry")。
     * 拆在应用层做,因为 SQL 里 = 匹配不到、LIKE 会误命中(见 CONTEXT.md)。
     */
    private List<String> splitMultiValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String value = piece.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
