package org.example.guide.crawler;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 物品页面源文 → {@link WikitextParams}。
 *
 * 只认 {@code {{Infobox item ...}}} 这一个模板块里的命名参数(数据源的结构化字段本来就出自它,
 * 见 resul.md)。解析是**容错**的:参数名先归一(小写 + 去掉非字母数字),
 * 所以 {@code HungerCooked} / {@code hunger_cooked} / {@code hunger cooked} 是同一个参数;
 * 数值解析不出来才抛异常(让这一条进失败明细,而不是把脏值静默当公式算)。
 *
 * 一个页面可能有**多个** Infobox(实测 3 个毒蘑菇页各放了普通版和中毒版两个框),
 * 靠 display 参数认领属于当前条目的那个框;认不出来时退回第一个框。
 */
@Component
public class ItemPageParser {

    /** 模板名的归一写法;数据源用的是 Infobox item,顺手接住 Item infobox 这个常见变体 */
    private static final Set<String> INFOBOX_TEMPLATES = Set.of("infoboxitem", "iteminfobox");

    /** 参数名的归一写法。模板参数写作 HungerCooked,BONUS_COOKED 也归一到同一个 key */
    private static final String HUNGER_COOKED = "hungercooked";
    private static final String BONUS_COOKED = "bonuscooked";
    private static final String HAS_COOKING_BONUS = "hascookingbonus";
    private static final String COOKING_NOTES = "cookingnotes";

    /** 用来认领"这个 Infobox 属于哪一条条目"的名字类参数,按优先级取 */
    private static final List<String> NAME_KEYS = List.of("display", "name");

    /** 模板源码里的注释不该混进取值(如 {{Infobox item|hunger = -30<!-- 测试用 -->}}) */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    public WikitextParams paramsFor(String pageWikitext, String displayName) {
        if (pageWikitext == null || displayName == null || displayName.isBlank()) {
            return WikitextParams.EMPTY;
        }

        List<Map<String, String>> infoboxes = infoboxesOf(pageWikitext);
        if (infoboxes.isEmpty()) {
            return WikitextParams.EMPTY;
        }

        Map<String, String> infobox = selectInfobox(infoboxes, displayName);
        return new WikitextParams(
                number(infobox, HUNGER_COOKED),
                number(infobox, BONUS_COOKED),
                lowerOrNull(infobox.get(HAS_COOKING_BONUS)),
                blankToNull(infobox.get(COOKING_NOTES)));
    }

    /**
     * 认领属于当前条目的那个 Infobox。
     *
     * 页面只有一个框时不用挑;有多个时按 display 参数认(毒蘑菇变体的 display 与页面名不同,
     * 数据源 Cargo 也是靠它把两个框拆成两行的)。都对不上时退回第一个框 ——
     * 覆盖值本来就少见,认不出就当页面没写,退回公式计算。
     */
    private static Map<String, String> selectInfobox(List<Map<String, String>> infoboxes, String displayName) {
        if (infoboxes.size() == 1) {
            return infoboxes.get(0);
        }
        for (Map<String, String> infobox : infoboxes) {
            for (String key : NAME_KEYS) {
                if (displayName.equalsIgnoreCase(blankToNull(infobox.get(key)))) {
                    return infobox;
                }
            }
        }
        return infoboxes.get(0);
    }

    /** 找出页面上所有 Infobox 块,按出现顺序返回各自的参数表 */
    private static List<Map<String, String>> infoboxesOf(String wikitext) {
        String text = HTML_COMMENT.matcher(wikitext).replaceAll("");
        List<Map<String, String>> found = new ArrayList<>();

        int cursor = 0;
        while (cursor < text.length()) {
            int open = text.indexOf("{{", cursor);
            if (open < 0) {
                break;
            }
            int close = matchingClose(text, open);
            if (close < 0) {
                // 括号不配对的残缺源码:后面的内容没法定位,放弃而不是猜
                break;
            }
            String body = text.substring(open + 2, close);
            cursor = close + 2;
            if (INFOBOX_TEMPLATES.contains(normalizeKey(templateName(body)))) {
                found.add(paramsOf(body));
            }
        }
        return found;
    }

    /** 从 open(指向 "{{")开始找配对的 "}}";找不到返回 -1 */
    private static int matchingClose(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            if (text.startsWith("{{", i)) {
                depth++;
                i += 2;
            } else if (text.startsWith("}}", i)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
                i += 2;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** 模板体到第一个顶层 "|" 为止是模板名 */
    private static String templateName(String body) {
        List<String> pieces = splitTopLevel(body);
        return pieces.isEmpty() ? body : pieces.get(0);
    }

    /** 把模板体拆成参数表:key 归一,重名取第一个;不是 key=value 的整段忽略 */
    private static Map<String, String> paramsOf(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        List<String> pieces = splitTopLevel(body);
        // 第 0 段是模板名,不是参数
        for (int i = 1; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            int equals = piece.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = normalizeKey(piece.substring(0, equals));
            if (!key.isEmpty()) {
                params.putIfAbsent(key, piece.substring(equals + 1).trim());
            }
        }
        return params;
    }

    /**
     * 按顶层 "|" 切开。
     *
     * 嵌套模板调用与方括号链接里的竖线不算分隔符 ——
     * 少了这层判断,一条带参数的模板或一张图片链接就会把参数切碎。
     */
    private static List<String> splitTopLevel(String body) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braces = 0;
        int brackets = 0;
        int i = 0;
        while (i < body.length()) {
            if (body.startsWith("{{", i)) {
                braces++;
                current.append("{{");
                i += 2;
            } else if (body.startsWith("}}", i)) {
                braces--;
                current.append("}}");
                i += 2;
            } else if (body.startsWith("[[", i)) {
                brackets++;
                current.append("[[");
                i += 2;
            } else if (body.startsWith("]]", i)) {
                brackets--;
                current.append("]]");
                i += 2;
            } else {
                char c = body.charAt(i);
                if (c == '|' && braces <= 0 && brackets <= 0) {
                    pieces.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
                i++;
            }
        }
        pieces.add(current.toString());
        return pieces;
    }

    /** 参数名的归一写法:小写 + 只留字母数字 */
    private static String normalizeKey(String raw) {
        StringBuilder normalized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toLowerCase(c));
            }
        }
        return normalized.toString();
    }

    /**
     * 数值参数:没写 → null。
     * 写了但解析不出数字 → 抛异常,让这一条进失败明细 —— 静默退回公式算出来的值
     * 是一个"看起来对但没人发现错了"的结果,比报错更难查。
     */
    private static Float number(Map<String, String> params, String key) {
        String raw = blankToNull(params.get(key));
        if (raw == null) {
            return null;
        }
        try {
            return Float.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("页面源文里的 " + key + " 不是数字:" + raw, e);
        }
    }

    private static String lowerOrNull(String raw) {
        String value = blankToNull(raw);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** 空值有两种形态:参数整个没写,或者写了但是空串。两者等价 */
    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
