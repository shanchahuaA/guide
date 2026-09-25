package org.example.guide.crawler;

import java.util.regex.Pattern;

/**
 * wikitext 取值的清洗。
 *
 * 结构化字段里混着 wikitext 标记（例如 location 的原值是 {@code [[Crash Site]]}），
 * 落库前要把标记剥干净。描述/成就那类长文本的清洗在后续票据里扩展，这里只做取值级的剥标记。
 */
public final class WikitextUtil {

    /** {@code [[目标]]} 与 {@code [[目标|显示文本]]} —— 两种写法都还原成显示文本 */
    private static final Pattern LINK = Pattern.compile("\\[\\[\\s*(?:[^\\[\\]|]*\\|)?\\s*([^\\[\\]|]*?)\\s*]]");

    /** 剥完链接后可能残留的方括号 / 花括号 */
    private static final Pattern RESIDUAL_MARKUP = Pattern.compile("[\\[\\]{}]");

    private WikitextUtil() {
    }

    /**
     * 剥掉取值里的 wikitext 标记。
     *
     * @return 剥好的纯文本;入参为 null 时返回 null
     */
    public static String stripMarkup(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = LINK.matcher(raw).replaceAll("$1");
        stripped = RESIDUAL_MARKUP.matcher(stripped).replaceAll("");
        stripped = stripped.replace("''", "");
        return stripped.trim();
    }
}
