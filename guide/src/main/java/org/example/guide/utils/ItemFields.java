package org.example.guide.utils;

import org.example.guide.crawler.IconFileNames;
import org.example.guide.crawler.TagDictionary;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图鉴条目的两个**计算字段**：{@code slug} 与 {@code primaryType}。
 *
 * <p>两者都不落库（表结构见 CLAUDE.md 数据模型节）。不落库不是省事：
 * 它们只服务于接口响应与一级导航，不参与任何查询条件,落库只会让"改一次归约规则就要重灌一次数据"。
 *
 * <p>口径见 {@code docs/接口契约.md} §0.3（slug）与 §0.4（primaryType）。
 */
public final class ItemFields {

    // 8 个取值是封闭集合（CONTEXT.md 主类型节），下面这张表、归约链与接口返回的组序都从这几个常量拼出来，
    // 免得同一个取值在三处各写一遍字面量
    private static final String FOOD = "FOOD";
    private static final String CONSUMABLE = "CONSUMABLE";
    private static final String EQUIPMENT = "EQUIPMENT";
    private static final String DEPLOYABLE = "DEPLOYABLE";
    private static final String AMULET = "AMULET";
    private static final String MYSTICAL = "MYSTICAL";
    private static final String MISC = "MISC";
    private static final String ENEMY = "ENEMY";

    /**
     * 主类型的取值与分组顺序,同时就是一级导航的格序（契约 §0.5）。
     *
     * <p>接口用它的下标给条目排序:不定顺序的话每次查询的返回次序都可能不同。
     */
    public static final List<String> PRIMARY_TYPE_ORDER = List.of(
            FOOD, CONSUMABLE, EQUIPMENT, DEPLOYABLE, AMULET, MYSTICAL, MISC, ENEMY);

    /** 只有 food 是靠"包含"判定的,其余取值要精确对上数据源原值 */
    private static final String FOOD_KEYWORD = "food";

    /**
     * 数据源原值 → 主类型取值。
     *
     * <p>不是同名重命名：{@code Mystical} 这个取值在数据源里的原值是 {@code Mystical item}。
     * {@code Berry} / {@code Mushroom} / {@code Natural food} / {@code Packaged food} 不在表里 ——
     * 它们全部同时带 {@code Food},由 {@link #FOOD_KEYWORD} 那条规则接住（已对库验证,见契约 §0.4）。
     */
    private static final Map<String, String> CODE_OF_TYPE_VALUE = Map.of(
            "Consumable", CONSUMABLE,
            "Equipment", EQUIPMENT,
            "Deployable", DEPLOYABLE,
            "Amulet", AMULET,
            "Mystical item", MYSTICAL,
            "Misc", MISC,
            "Enemy", ENEMY);

    private ItemFields() {
    }

    /**
     * slug：详情页的路径参数,也是条目在接口里的对外标识。
     *
     * <p>由 {@code nameEn} 派生。{@code nameEn} 是采集 upsert 的去重口径,天然唯一,所以 slug 也唯一,
     * 不必拼 id —— 拼了 id 换机器重灌数据就会变。
     *
     * <p><b>规则直接复用 {@link IconFileNames} 的字符白名单再全小写,不另造一套。</b>
     * 两处规则一旦分叉,就会出现"图标文件名合法、slug 却取不到"这种只在少数名字上发作的错,
     * 而库里恰好有一组会撞车的名字（{@code Bugle} / {@code Bugle?} / {@code Bugle Shroom (Poisonous)}）,
     * 靠删特殊字符或丢括号内容都会硬撞。
     *
     * <p>{@link Locale#ROOT} 是必要的:默认 locale 下土耳其语的 {@code I.toLowerCase()} 会得到点less 的
     * {@code ı},{@code First Aid Kit} 这种带大写 I 的名字会派生出另一个 slug。
     */
    public static String slugOf(Item item) {
        return IconFileNames.sanitize(item.getNameEn()).toLowerCase(Locale.ROOT);
    }

    /**
     * primaryType：一级导航的落点,一个条目有且只有一个。
     *
     * <p>归约顺序（契约 §0.4）：
     * <ol>
     *   <li>type 里含 {@code food}（不分大小写）即 {@code FOOD};</li>
     *   <li>否则按 {@link #PRIMARY_TYPE_ORDER} 的顺序取第一个命中的取值;</li>
     *   <li>都没有则 {@code MISC} —— 与 {@code TagDictionary} 同一套容错口径：
     *       数据源新增一个未知 type 时归到杂物,而不是把整批接口炸掉。</li>
     * </ol>
     *
     * <p>跨界条目（{@code Scorpion} 同时带 {@code Food} 与 {@code Enemy}）落在 {@code FOOD}:
     * 一级导航必须收敛成一个入口,主类型是有意冗余的。
     */
    public static String primaryTypeOf(Item item) {
        List<String> typeValues = typeValuesOf(item);

        for (String value : typeValues) {
            if (value.toLowerCase(Locale.ROOT).contains(FOOD_KEYWORD)) {
                return FOOD;
            }
        }

        // 顺序即优先级。Enemy 排在末尾,所以只有"纯敌人"才落到 ENEMY（带 Misc 的敌人走 MISC）
        for (String code : PRIMARY_TYPE_ORDER) {
            if (FOOD.equals(code)) {
                continue;
            }
            for (String value : typeValues) {
                if (code.equals(CODE_OF_TYPE_VALUE.get(value))) {
                    return code;
                }
            }
        }

        return MISC;
    }

    /**
     * 条目的 type 维度取值。
     *
     * <p>只看 {@code tag.code = "type"} 这一个维度 —— biome / rarity 里出现同名取值不参与归约
     * （{@code Peak} 既是生态也是位置就是现成的例子）。
     */
    private static List<String> typeValuesOf(Item item) {
        if (item.getTag() == null) {
            return List.of();
        }
        return item.getTag().stream()
                .filter(tag -> TagDictionary.TYPE.equals(tag.getCode()) && tag.getValue() != null)
                .map(ItemTag::getValue)
                .toList();
    }
}
