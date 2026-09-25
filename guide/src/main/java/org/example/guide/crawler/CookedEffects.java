package org.example.guide.crawler;

import org.example.guide.pojo.Effect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 熟食数值:把生食行 + 页面源文里的覆盖值拼成 {@code *_COOKED} 效果(存**总量**口径)。
 *
 * 数据源的模板在渲染时才按公式现算熟食值,从不落库,所以这一块是自己算的:
 *
 * <ul>
 *   <li>{@code HUNGER_COOKED} = 覆盖值 HungerCooked,否则 生值×2;</li>
 *   <li>{@code BONUS_COOKED} = 覆盖值 BonusCooked,否则 生值×1.5(生值没有时按模板的兜底给 10);</li>
 *   <li>{@code HasCookingBonus} = no/breaks 的条目**不生成**上面两个值(烹饪不给饱食/加成);</li>
 *   <li>{@code POISON_COOKED} / {@code THORNS_COOKED} / {@code SPORES_COOKED}:**默认原样保留**
 *       (含 duration/startDelay)—— 毒蘑菇的毒、蘑菇的孢子煮熟了都还在,这是票内明确的有效信息;</li>
 *   <li>两条**显式写 0** 的例外(煮熟"清零"本身是有效信息):浆果(POISON / THORNS)、
 *       Shroomberry(浆果 + 蘑菇,SPORES)。</li>
 * </ul>
 *
 * 两条通用约束:
 * <ul>
 *   <li>**只有食物生成熟食值**(type 里含 Food 这个标签),道具一个都不生成 ——
 *       这才是票内三个反例(First Aid Kit / Dynamite / Fortified Milk)判否的**静态**依据:
 *       它们不是食物。Fortified Milk 虽然带 Food 标签,靠页面上的 HasCookingBonus=no 再挡一道;</li>
 *   <li>算出来是 0 的不写入(0 没有可对比的信息),上面两条显式写 0 的例外除外。</li>
 * </ul>
 */
@Component
public class CookedEffects {

    private static final String FOOD = "Food";
    private static final String BERRY = "Berry";
    private static final String MUSHROOM = "Mushroom";

    private static final String HUNGER_COOKED = "HUNGER_COOKED";
    private static final String BONUS_COOKED = "BONUS_COOKED";
    private static final String POISON_COOKED = "POISON_COOKED";
    private static final String SPORES_COOKED = "SPORES_COOKED";
    private static final String THORNS_COOKED = "THORNS_COOKED";

    /** 模板公式:熟食饱食总量 = 生值 × 2 */
    private static final float HUNGER_FACTOR = 2f;

    /** 模板公式:熟食加成总量 = 生值 × 1.5 */
    private static final float BONUS_FACTOR = 1.5f;

    /** 生值没有加成时,模板按 10 给熟食加成("没加成的食物烤熟给 10 加成") */
    private static final float BONUS_FALLBACK = 10f;

    /**
     * @param row             数据源的一行(生食侧字段已在 HTTP 边界收口)
     * @param params          该条目所在页面源文里提取出的覆盖值
     * @param typeValues      已经拆开的 type 标签,用来判断是不是食物 / 浆果 / 蘑菇
     * @return 该条目的熟食效果;不是食物、或者一个熟食值都没算出来时返回空列表
     */
    public List<Effect> build(CargoItemRow row, WikitextParams params, List<String> typeValues) {
        if (!hasType(typeValues, FOOD)) {
            return List.of();
        }
        boolean berry = hasType(typeValues, BERRY);
        List<Effect> cooked = new ArrayList<>();

        if (!params.suppressesCookingBonus()) {
            addDerived(cooked, HUNGER_COOKED, params.hungerCooked(), scaled(row.getHunger(), HUNGER_FACTOR));
            addDerived(cooked, BONUS_COOKED, params.bonusCooked(), cookedBonus(row.getBonus()));
        }

        if (berry) {
            // 浆果煮熟毒/刺清零。只有生值存在时才写:本来就没有毒的东西,"煮熟后为 0"是句空话
            addExplicitZero(cooked, POISON_COOKED, row.getPoison());
            addExplicitZero(cooked, THORNS_COOKED, row.getThorns());
        } else {
            // 其余条目毒/刺原样保留(含附属值)—— 毒蘑菇的毒煮不掉
            addNonZero(cooked, POISON_COOKED, row.getPoison(), row.getPoisonTime(), row.getPoisonStart());
            addNonZero(cooked, THORNS_COOKED, row.getThorns(), null, null);
            // 孢子同理:非浆果的蘑菇煮了还是那些孢子
            addNonZero(cooked, SPORES_COOKED, row.getSpores(), null, null);
        }

        // Shroomberry = 浆果 + 蘑菇:煮熟后孢子归零(非浆果的孢子走上面的原样保留分支)
        if (berry && hasType(typeValues, MUSHROOM)) {
            addExplicitZero(cooked, SPORES_COOKED, row.getSpores());
        }

        return cooked;
    }

    /**
     * 可烹饪判据(见 CONTEXT.md):熟食效果里**至少有一个非零数值**。
     *
     * 显式写的 0 不算 —— "煮掉了"是有效信息,要显示出来,但它不构成"有熟食可对比",
     * 所以只写了 0 的条目不该带 cookable 旗标。
     */
    public static boolean containsNonZero(List<Effect> cooked) {
        return cooked.stream().anyMatch(effect -> effect.getValue() != null && effect.getValue() != 0f);
    }

    /** 覆盖值优先,否则用公式算出来的值;两边都没有 → 不生成 */
    private static void addDerived(List<Effect> cooked, String code, Float override, Float derived) {
        addNonZero(cooked, code, override != null ? override : derived, null, null);
    }

    /** 算出 0 的不写入:0 是没有可对比信息的值。显式写 0 的例外走 {@link #addExplicitZero} */
    private static void addNonZero(List<Effect> cooked, String code, Float value, Float duration, Float startDelay) {
        if (value == null || value == 0f) {
            return;
        }
        cooked.add(effect(code, value, duration, startDelay));
    }

    /** 显式写 0:生值存在时才写(没有的东西谈不上"清零") */
    private static void addExplicitZero(List<Effect> cooked, String code, Float rawValue) {
        if (rawValue == null) {
            return;
        }
        cooked.add(effect(code, 0f, null, null));
    }

    private static Float scaled(Float raw, float factor) {
        return raw == null ? null : raw * factor;
    }

    private static Float cookedBonus(Float rawBonus) {
        return rawBonus == null ? BONUS_FALLBACK : rawBonus * BONUS_FACTOR;
    }

    private static boolean hasType(List<String> typeValues, String token) {
        return typeValues.stream().anyMatch(token::equalsIgnoreCase);
    }

    private static Effect effect(String code, Float value, Float duration, Float startDelay) {
        Effect effect = new Effect();
        effect.setCode(code);
        effect.setValue(value);
        effect.setDuration(duration);
        effect.setStartDelay(startDelay);
        return effect;
    }
}
