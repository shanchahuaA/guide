package org.example.guide.ai;

import org.example.guide.crawler.TagDictionary;
import org.example.guide.pojo.Effect;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;
import org.example.guide.utils.ItemFields;

import java.util.List;
import java.util.Locale;

/**
 * 把图鉴数据压成喂给大模型的上下文（契约 §7.4「后端查图鉴数据组装上下文，**不联网**」）。
 *
 * <p>数据来自**图鉴缓存**（{@code cache/ItemCache.getAll()}），不是直查库 —— 缓存里就是全量 134 条，
 * 与接口同源，不会出现"AI 讲的数字和图鉴里对不上"。
 *
 * <p><b>为什么先按问题筛一遍、而不是把 134 条全塞进去</b>：全量条目连同描述约 5 万字，
 * 每问一句都要重发一次，还容易把不相关条目的数字串到一起。这里只挑与问题相关的条目，
 * 筛不出任何一条时才回落到全量（见 {@link #build}）。
 *
 * <p>喂的是**中文名 + 中文描述 + 生熟数值**，英文那半不下发（与详情接口同一口径：中文才是
 * 给用户看的那份）。条目里出现生熟两份 effect，{@code _COOKED} 后缀在这一层翻译成
 * "熟"字，不让模型去猜这个后缀什么意思。
 */
public final class ItemContextBuilder {

    private static final String COOKED_SUFFIX = "_COOKED";

    /** 筛出来的条目上限。再多也不影响正确性，只是白花 token */
    private static final int MAX_MATCHED_ITEMS = 12;

    /** 每条描述的截断长度。库里最长 391 字，截断只兜住将来采集进来的更长文本 */
    private static final int MAX_DESCRIPTION_CHARS = 400;

    /** 回落全量（没有一条名字命中问题）时给出的条目数上限。全量 134 条太长，取前面这些当"图鉴概览" */
    private static final int MAX_FALLBACK_ITEMS = 40;

    private ItemContextBuilder() {
    }

    /**
     * 组装上下文。
     *
     * @param items    图鉴全量条目（来自缓存）
     * @param question 用户原问题。用它按名字做一次粗筛：问题里写了"蘑菇"，就把名字带"蘑菇"的条目挑出来
     */
    public static String build(List<Item> items, String question) {
        if (items == null || items.isEmpty()) {
            return "（图鉴数据暂时不可用）";
        }

        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<Item> matched = items.stream()
                .filter(item -> matches(item, q))
                .limit(MAX_MATCHED_ITEMS)
                .toList();

        // 一条都筛不出来（问的是"蘑菇有什么用"这种描述性问法，而条目叫"喇叭菇"）时回落到概览：
        // 宁可多给一点，也不要让模型在"没有任何数据"的情况下开始编
        List<Item> chosen = matched.isEmpty()
                ? items.stream().limit(MAX_FALLBACK_ITEMS).toList()
                : matched;

        StringBuilder sb = new StringBuilder();
        sb.append("以下是图鉴中与本问题相关的条目（共 ").append(items.size()).append(" 条，摘录 ")
          .append(chosen.size()).append(" 条）：\n\n");
        for (Item item : chosen) {
            sb.append(render(item)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 名字命中：中文名或英文名出现在问题里、或问题里的词出现在名字里。
     *
     * <p>两个方向都要判 —— "蘑菇"是条目"喇叭菇"的**后半截**，只判"名字出现在问题里"会漏；
     * 而"Hot Dog 有什么用"里问题整句都比名字长，只判反方向也会漏。
     */
    private static boolean matches(Item item, String lowerQuestion) {
        String nameZh = item.getNameZh();
        String nameEn = item.getNameEn();
        if (nameZh != null && !nameZh.isEmpty() && lowerQuestion.contains(nameZh.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (nameEn != null && !nameEn.isEmpty() && lowerQuestion.contains(nameEn.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // 反方向只对中文名做：中文名多为 2-4 字，切出来的子串有意义；
        // 英文名倒过来会把 "food" 这种泛词切得满库都是
        if (nameZh != null && nameZh.length() >= 2) {
            for (int i = 0; i + 2 <= nameZh.length(); i++) {
                if (lowerQuestion.contains(nameZh.substring(i, i + 2).toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 一条条目的文本形态：名字、类型、重量、生态/稀有度、生熟数值、描述 */
    private static String render(Item item) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(nullToEmpty(item.getNameZh())).append("（").append(nullToEmpty(item.getNameEn())).append("）】");
        sb.append(" 主类型=").append(ItemFields.primaryTypeOf(item));
        if (item.getWeight() != null) {
            sb.append("，重量=").append(item.getWeight());
        }
        String tagText = renderTags(item);
        if (!tagText.isEmpty()) {
            sb.append("，").append(tagText);
        }
        sb.append('\n');

        String raw = renderEffects(item, false);
        String cooked = renderEffects(item, true);
        if (!raw.isEmpty()) {
            sb.append("  生：").append(raw).append('\n');
        }
        if (!cooked.isEmpty()) {
            sb.append("  熟：").append(cooked).append('\n');
        }

        String description = item.getDescriptionZh();
        if (description != null && !description.isBlank()) {
            sb.append("  描述：").append(truncate(description)).append('\n');
        }
        return sb.toString();
    }

    /** 生态与稀有度：问"哪里捡"、"稀有吗"这类问题要靠它，其余维度（source/location/flag）回答里基本用不上 */
    private static String renderTags(Item item) {
        if (item.getTag() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ItemTag tag : item.getTag()) {
            if (tag == null || tag.getValue() == null) {
                continue;
            }
            if (TagDictionary.BIOME.equals(tag.getCode())) {
                sb.append("生态=").append(nullToEmpty(tag.getNameZh())).append('/').append(tag.getValue()).append('，');
            } else if (TagDictionary.RARITY.equals(tag.getCode())) {
                sb.append("稀有度=").append(nullToEmpty(tag.getNameZh())).append('/').append(tag.getValue()).append('，');
            }
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 生值或熟值那一串。
     *
     * <p>{@code _COOKED} 后缀在这里翻译掉：模型看到的直接是"熟"那一行，
     * 不必自己去理解后缀语义（它猜错的代价是给用户一个错的数字）。
     */
    private static String renderEffects(Item item, boolean cooked) {
        if (item.getEffect() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Effect effect : item.getEffect()) {
            if (effect == null || effect.getCode() == null) {
                continue;
            }
            boolean isCooked = effect.getCode().endsWith(COOKED_SUFFIX);
            if (isCooked != cooked) {
                continue;
            }
            String code = isCooked
                    ? effect.getCode().substring(0, effect.getCode().length() - COOKED_SUFFIX.length())
                    : effect.getCode();
            sb.append(code).append('=').append(effect.getValue());
            if (effect.getDuration() != null) {
                sb.append("(持续 ").append(effect.getDuration()).append(" 秒)");
            }
            sb.append('，');
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_DESCRIPTION_CHARS) {
            return text;
        }
        return text.substring(0, MAX_DESCRIPTION_CHARS) + "…";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
