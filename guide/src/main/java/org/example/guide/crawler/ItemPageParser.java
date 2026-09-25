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
 *
 * <p>页面级自由文本(#15)同样从这份源文里取:{@link #pageTextFor} 产出 description 与
 * achievement。两者共用同一套「切掉模板块 / 认 Infobox」的扫描逻辑,所以留在同一个类里，
 * 而不是再写一个各扫一遍的轻量解析器。
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
    private static final String LIST_NOTES = "listnotes";

    /** 用来认领"这个 Infobox 属于哪一条条目"的名字类参数,按优先级取 */
    private static final List<String> NAME_KEYS = List.of("display", "name");

    /** 徽章模板的归一写法。实测两种写法都出现过:{{Badge|Competitive Eating}} 与 {{badge|Resourcefulness}} */
    private static final String BADGE_TEMPLATE = "badge";

    /** 正文里的章节标题(== X == / === X ===),从这里往后都是 Trivia / Gallery / Patch history 一类的附属内容 */
    private static final Pattern SECTION_HEADING = Pattern.compile("^(={2,})\\s*(.+?)\\s*\\1$");

    /** cookingNotes 拼进 description 时的前缀(票面写法,中文全角冒号) */
    private static final String COOKING_PREFIX = "烹饪：";

    /** 模板源码里的注释不该混进取值(如 {{Infobox item|hunger = -30<!-- 测试用 -->}}) */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** 段落之间的空行;数据源实测换行是 \n,但 \r\n 一并接住 */
    private static final Pattern BLANK_LINE = Pattern.compile("(?:\\r?\\n\\s*){2,}");

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
     * 页面级自由文本 → description 与 achievement(见 #15)。
     *
     * <p><b>为什么保留「首段起至首个章节标题」的**全部**段落,而不是只取第一段。</b>
     * 票面写的是"页面首段散文",但实测页面结构与这句话冲突:Hot Dog 的徽章引用
     * ({@code {{Badge|Competitive Eating}}})在第 4 段,严格只取首段会让验收标准
     * "description 含徽章名"永远不成立。图鉴要的是"这个物品的说明文字",附属章节
     * ({@code == Trivia ==} / {@code == Gallery ==} / {@code == Patch history ==})
     * 才是明确该排除的东西 —— 所以按**章节标题**截断,而不是按段落数截断。
     *
     * <p>整段原文里最脏的两处(实测):
     * <ul>
     *   <li>前置模板块 —— {@code {{Ambox|...}}}(已移除条目)与 {@code {{For|...}}
     *       (Peak (biome)) 都不是正文。Ambox 里还有 {@code icon = Speed Climber Badge.png},
     *       不先切掉的话"Badge"会被当成徽章引用;</li>
     *   <li>没有 Infobox item 的页面({@code Cooking} / {@code Campfire} / {@code Scout} /
     *       {@code Luggage} 等)—— 首块是普通模板甚至裸 {@code {{Infobox}}。
     *       兜底不是"放弃",而是**跳过前置模板块继续取正文**:这些页面的散文一样可用。</li>
     * </ul>
     *
     * <p>解析是**不抛异常**的:这是展示用的自由文本,取不到就留空(或有几段算几段),
     * 不该因为它把一条数据挡在库外 —— 与熟食侧"数值解析不出就抛"的取舍刻意相反。
     *
     * @return 任何一段都取不到时返回 {@link PageText#EMPTY}
     */
    public PageText pageTextFor(String pageWikitext) {
        return pageTextFor(pageWikitext, null);
    }

    /**
     * 页面级自由文本,并把 {@code {{PAGENAME}}} 还原成条目名。
     *
     * @param pageName 条目英文名(display 或页面名),用于替换散文里的 {@code {{PAGENAME}}};
     *                 为 null 时该占位符剥成空串,description 里不会留下 "PAGENAME" 字面量
     */
    public PageText pageTextFor(String pageWikitext, String pageName) {
        if (pageWikitext == null || pageWikitext.isBlank()) {
            return PageText.EMPTY;
        }
        String text = HTML_COMMENT.matcher(pageWikitext).replaceAll("");

        // listNotes 与 cookingNotes 都取自 Infobox(页面上可能有多个框,取第一个)。
        // 目的只是取文本、不参与条目认领,所以不需要 display —— 变体页两个框的说明文字本来就一样
        Map<String, String> infobox = infoboxesOf(text).stream().findFirst().orElse(Map.of());

        return new PageText(
                buildDescription(text, infobox, pageName),
                badgesOf(text));
    }

    /**
     * description = 正文段落 + listNotes + cookingNotes(三段拼接)。
     *
     * 任意一段为空就跳过那一段,不做"空段拼出多余空格"这种事 ——
     * 实测 Marshmallow 这类页面既没有 listNotes 也没有 cookingNotes,结果就是一句散文。
     */
    private static String buildDescription(String text, Map<String, String> infobox, String pageName) {
        List<String> parts = new ArrayList<>();

        String prose = proseOf(text, pageName);
        if (prose != null) {
            parts.add(prose);
        }
        String listNotes = wikitextToPlain(infobox.get(LIST_NOTES), pageName);
        if (listNotes != null) {
            parts.add(listNotes);
        }
        String cookingNotes = wikitextToPlain(infobox.get(COOKING_NOTES), pageName);
        if (cookingNotes != null) {
            // 前缀是票面写死的"烹饪：" —— 与"来源"/"获取说明"那类内容区分开
            parts.add(COOKING_PREFIX + cookingNotes);
        }

        return parts.isEmpty() ? null : String.join(" ", parts);
    }


    /**
     * 取正文:从首个 Infobox 宏之后开始,跳过紧跟的其它前置模板,截到第一个章节标题为止。
     *
     * 先切掉前置模板再找正文,是为了不让模板参数文本混进散文 ——
     * {@code {{For|the article about the game|Peak (game)}} 之后的第一段} 才是正文。
     *
     * @return 剥好标记、段落之间单空格分隔的正文;没有正文时返回 null
     */
    private static String proseOf(String text, String pageName) {
        int cursor = skipLeadingTemplates(text);
        StringBuilder prose = new StringBuilder();
        for (String paragraph : paragraphsOf(text.substring(cursor))) {
            if (hasSectionHeading(paragraph)) {
                // 章节标题之后全是 Trivia / Gallery / Patch history 一类的附属内容,到此为止。
                // 判据是"某一行本身是标题",不是"整段等于标题" —— 段落里常混着
                // __forcetoc__ 这类行为开关或标题后的正文,按整段比会漏掉这一刀
                break;
            }
            // 列表/说明类的行首标记(* # ; :)不是内容
            String plain = WikitextUtil.stripMarkup(paragraph, pageName)
                    .replaceAll("(?m)^\\s*[*#;:]+\\s*", "").trim();
            String folded = plain.replaceAll("\\s+", " ").trim();
            if (folded.isEmpty()) {
                // 纯 File 链接、{{Navbox}} 这类剥完什么都不剩的段落:不是正文,跳过继续看下一段
                continue;
            }
            if (prose.length() > 0) {
                prose.append(' ');
            }
            prose.append(folded);
        }
        return prose.length() == 0 ? null : prose.toString();
    }

    /**
     * 跳过正文之前的所有顶层模板（以及模板之间的空白）。
     *
     * <p><b>为什么是"所有"而不是"第一个 Infobox"。</b>
     * 实测页面的前置模板不止一个、顺序也不固定：
     * {@code {{Ambox}} + {{Infobox item}}}（已移除条目）、
     * {@code {{For}} + {{Infobox location}} + {{Infobox item}}}、
     * {@code {{Infobox item}}} 单独一个，还有没有 Infobox 的页面
     * （{@code Cooking} / {@code Campfire}）。逐个往后跳能把这一堆写法一次收干净；
     * 只跳一个则会把后面那个模板的**模板名**当成正文首句
     * （实测漏出的正是 "Infobox item The Bugle? is…" 与 "For Infobox location The Peak is…"）。
     *
     * <p>跳完之后若是文本（而不是又一个模板），那就是正文起点。
     */
    private static int skipLeadingTemplates(String text) {
        int cursor = 0;
        while (cursor < text.length()) {
            int open = text.indexOf("{{", cursor);
            if (open < 0) {
                break;
            }
            // 模板与游标之间还有实质文本 → 正文已经开始,不再往后跳
            if (!text.substring(cursor, open).isBlank()) {
                break;
            }
            int close = matchingClose(text, open);
            if (close < 0) {
                // 括号不配对的残缺源码:后面的内容没法定位,退回当前位置
                break;
            }
            cursor = close + 2;
        }
        return cursor;
    }

    /** 段落里出现 {@code == X ==} 这样的独立标题行时为 true */
    private static boolean hasSectionHeading(String paragraph) {
        for (String line : paragraph.split("\\r?\\n")) {
            if (SECTION_HEADING.matcher(line.trim()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 按空行切段;每段内部的换行保留,由调用方各自处理 */
    private static List<String> paragraphsOf(String body) {
        List<String> paragraphs = new ArrayList<>();
        for (String piece : BLANK_LINE.split(body)) {
            if (!piece.isBlank()) {
                paragraphs.add(piece.trim());
            }
        }
        return paragraphs;
    }

    /**
     * 取 Infobox 里的说明类参数(listNotes / cookingNotes)。
     *
     * 实测这两处都写成 {@code List-notes = * '''Source''': …} 的**列表**形态,
     * 所以剥完标记后要把行首的项目符号收掉,否则 description 里会冒出孤零零的 {@code *}。
     */
    private static String wikitextToPlain(String raw, String pageName) {
        String stripped = WikitextUtil.stripMarkup(raw, pageName);
        if (stripped == null || stripped.isEmpty()) {
            return null;
        }

        StringBuilder plain = new StringBuilder();
        for (String line : stripped.split("\\r?\\n")) {
            // 行首的 * / # / ; 是列表标记, : 是缩进标记 —— 都不是内容
            String content = line.replaceFirst("^\\s*[*#;:]+\\s*", "").trim();
            if (content.isEmpty()) {
                continue;
            }
            if (plain.length() > 0) {
                plain.append(' ');
            }
            plain.append(content);
        }
        return plain.length() == 0 ? null : plain.toString();
    }

    /**
     * 全文收集徽章引用名({@code {{Badge|X}}} / {@code {{badge|X}}}),按出现顺序去重,逗号拼接。
     *
     * <p>扫描范围是**整个页面**而不只是正文:徽章引用可能出现在附属章节里
     * (实测 Cooked Bird 的 {@code {{badge|Resourcefulness}}} 在正文,
     * 但这类"吃掉/做到某事解锁徽章"的句子没有固定位置)。
     *
     * <p>只认模板名是 badge 的宏:已移除条目页首的 {@code {{Ambox|...|icon = Speed Climber Badge.png}}
     * 里也有 "Badge" 字样,按模板名取就天然不会误命中 —— 这也是不做"文件名含 Badge 就算"那种猜测式匹配的原因。
     *
     * @return 逗号分隔的徽章名;一个都没有时返回 null(该列留空,条目照常落库)
     */
    private static String badgesOf(String text) {
        List<String> badges = new ArrayList<>();
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

            List<String> pieces = splitTopLevel(body);
            if (pieces.isEmpty() || !BADGE_TEMPLATE.equals(normalizeKey(pieces.get(0)))) {
                continue;
            }
            // {{Badge|Competitive Eating}} —— 第 0 段是模板名,第 1 段才是徽章名
            for (int i = 1; i < pieces.size(); i++) {
                String name = wikitextToPlain(pieces.get(i), null);
                if (name != null && !badges.contains(name)) {
                    badges.add(name);
                }
            }
        }
        return badges.isEmpty() ? null : String.join(", ", badges);
    }

    /**
     * 页面级自由文本。
     *
     * @param description 正文段落 + listNotes + cookingNotes(可能有"烹饪："前缀);取不到时为 null
     * @param achievement 逗号拼接的徽章名;页面没有徽章引用时为 null
     */
    public record PageText(String description, String achievement) {

        /** 源文不可得、或页面上什么都没有时的兜底:两个字段都留空 */
        public static final PageText EMPTY = new PageText(null, null);
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
