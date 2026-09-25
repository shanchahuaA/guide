package org.example.guide.crawler;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * wikitext 取值的清洗。
 *
 * 结构化字段里混着 wikitext 标记（例如 location 的原值是 {@code [[Crash Site]]}），
 * 落库前要把标记剥干净。长文本（描述/成就，见 #15）也走这一份 ——
 * 取值级与段落级的清洗共用同一条规则，避免两处各剥一套、结果对不上。
 */
public final class WikitextUtil {

    /**
     * {@code [[File:…]]} / {@code [[Image:…]]} —— **整块丢弃**。
     *
     * <p>图片链接不是句子的一部分，而且实测写法很脏：
     * {@code [[File:Dynamite_closeup.png|thumb|240px|Dynamite can be found on the ground in [[Mesa]].]]}
     * 有三段参数、最后一段里还嵌着一层链接。按"取显示文本"处理会把图片说明当正文，
     * 而且内层链接会让外层匹配不上 —— 所以先整块删，再做通用链接替换。
     */
    private static final Pattern MEDIA_LINK =
            Pattern.compile("\\[\\[\\s*(?:File|Image)\\s*:.*?]]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * {@code [[目标]]} 与 {@code [[目标|显示文本]]} —— 两种写法都还原成显示文本。
     *
     * <p>两种写法分开写是因为替换的目标组不同：带竖线时显示文本在**第 2 组**，
     * 不带竖线时目标本身在第 3 组。合在一个可选组里会让 {@code $1} 在这两种情况下
     * 指向不同的东西 —— 实测的后果是 {@code [[Gloom]]} 被替换成空串。
     */
    private static final Pattern LINK =
            Pattern.compile("\\[\\[\\s*([^\\[\\]|]*)\\|([^\\[\\]]*?)\\s*]]|\\[\\[\\s*([^\\[\\]|]*?)\\s*]]");

    /** 带说明的站外链 {@code [https://… 文字]} → 文字；不带说明的 {@code [https://…]} → 空 */
    private static final Pattern EXTERNAL_LINK =
            Pattern.compile("\\[\\s*(?:https?|ftp)://\\S*\\s*([^\\]]*?)\\s*]");

    /** {@code <gallery>…</gallery>} / {@code <ref>…</ref>} 这类整块丢弃的扩展标签 */
    private static final Pattern DROP_ELEMENT =
            Pattern.compile("<(gallery|ref)\\b[^>]*>.*?</\\1\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * 徽章引用 {@code {{Badge|X}}} —— **整块丢弃**。
     *
     * <p>它夹在句子里（实测 "grants the player the {@code {{Badge|Competitive Eating}}}"）。
     * 若走通用规则只留模板名，description 会读成"grants the player the Badge."——
     * 半截话，而且徽章名本来就已经收进 {@code achievement} 列了，正文里再出现一次
     * 既不完整也重复。所以整块删掉，句子读成"grants the player the."依然别扭，
     * 但比留下一个残缺的模板名更接近原文意图（该信息已被 achievement 承载）。
     */
    private static final Pattern BADGE_TEMPLATE =
            Pattern.compile("\\{\\{\\s*badge\\s*(?:\\|[^{}]*)?\\}\\}", Pattern.CASE_INSENSITIVE);

    /** 自闭合标签与剩余的单标签（{@code <br/>}、{@code </ref>}） */
    private static final Pattern TAG = Pattern.compile("<[^<>]+>");

    /**
     * 模板调用 {@code {{模板名|参数…}}}。
     *
     * **只留模板名，参数一律丢弃** ——
     * <ul>
     *   <li>取值级的老用法本来就假设链接里没有模板（{@code [[Crash Site]]}），
     *       剥到模板块时整块不收也安全；</li>
     *   <li>长文本里必须留下 {@code {{PAGENAME}}} 的**名字**（剥成 {@code PAGENAME}，
     *       否则句子会缺主语），但绝不能连参数一起留：{@code {{Badge|Competitive Eating}}}
     *       如果留下 {@code "Badge|Competitive Eating"}，那既不是徽章名也不是文字，
     *       正是验收标准里"含徽章名 Competitive Eating"要抓的那种脏串。</li>
     * </ul>
     * 只匹配单层（不含嵌套花括号）—— 嵌套模板由外层先匹配掉，参数里的内层会被一起丢掉。
     */
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^{}|]*)(?:\\|[^{}]*)?\\}\\}");

    /**
     * 纯排版/链接类模板：**整块替换成它的第一个位置参数**（没有参数就丢掉）。
     *
     * <p>{@code {{Peak game}}}、{@code {{Item|Marshmallow}}}、{@code {{Injury|25}}}
     * 在页面上渲染成链接文字或图标，**模板名本身不是句子的一部分**。原样留着名字会让
     * description 读成"found near the campfires in Peak game"、
     * "either a Item (62.5% chance)…instead of a Item"这种半截话；
     * 而整块删空又会把句子删出窟窿（"either a (62.5% chance)"、"in  that was"）。
     * 取位置参数正好是页面上真正显示的东西：{@code {{Item|Marshmallow}}} → "Marshmallow"。
     *
     * <p><b>为什么是白名单而不是通用规则。</b>
     * "参数第一个就是显示文本"在数据源上**不成立**（{@code {{Badge|Competitive Eating}}}
     * 的第一个参数是徽章名，而 {@code {{Injury|25}}} 的第一个参数是数值），
     * 所以只列实测见过、且确认参数语义的这几个。猜的代价不对称：
     * 猜错会把 {@code {{PAGENAME}}} 一起吃掉，那恰好是必须留下的那一个。
     */
    private static final Set<String> DISPLAY_ONLY_TEMPLATES = Set.of(
            "peak game", "peakgame", "item", "injury", "hunger", "poison", "spores",
            "bonus", "heat", "cold", "curse", "drowsy", "thorns", "petrify", "morale boost");

    /**
     * 无参数、但渲染成**固定文字**的模板：整块替换成那个文字。
     *
     * <p>{@code {{Peak game}}} 的模板源码是 {@code ''[[Peak (game)|PEAK]]''} ——
     * 它就是游戏名本身，页面上显示为 "PEAK"。删空会读成"a natural food in found near…"
     * （实测出现过的窟窿），所以给一个固定的替换值，而不是丢掉。
     * 这张表是**渲染结果**的映射，不是猜测：取值来自模板源码。
     */
    private static final Map<String, String> FIXED_TEXT_TEMPLATES = Map.of(
            "peak game", "PEAK",
            "peakgame", "PEAK");

    /** 匹配排版类模板整块（含参数），交给回调取位置参数（也用于"整块丢弃"的重载） */
    private static final Pattern DISPLAY_ONLY = displayOnlyPattern();

    /** 只匹配模板名 + 第一个位置参数；无参数时不匹配 */
    private static final Pattern DISPLAY_ONLY_FIRST_ARG =
            Pattern.compile("\\{\\{\\s*(?:" + displayOnlyNames() + ")\\s*\\|\\s*([^{}|]+?)\\s*(?:\\|[^{}]*)?\\}\\}",
                    Pattern.CASE_INSENSITIVE);

    /** 无参数的排版类模板：整块丢掉（渲染成固定文字的走 FIXED_TEXT_TEMPLATES，不放这里） */
    private static final Pattern DISPLAY_ONLY_NO_ARG =
            Pattern.compile("\\{\\{\\s*(?:" + displayOnlyNames() + ")\\s*\\}\\}", Pattern.CASE_INSENSITIVE);

    /**
     * 渲染成固定文字、且**无参数**的模板（{@code {{Peak game}}}）：
     * 捕获模板名，由回调按 {@link #FIXED_TEXT_TEMPLATES} 查表替换。
     */
    private static final Pattern FIXED_TEXT =
            Pattern.compile("\\{\\{\\s*(" + fixedTextNames() + ")\\s*\\}\\}", Pattern.CASE_INSENSITIVE);

    private static String fixedTextNames() {
        StringBuilder names = new StringBuilder();
        for (String name : FIXED_TEXT_TEMPLATES.keySet()) {
            if (names.length() > 0) {
                names.append('|');
            }
            names.append(quoteWords(name));
        }
        return names.toString();
    }

    private static Pattern displayOnlyPattern() {
        return Pattern.compile("\\{\\{\\s*(?:" + displayOnlyNames() + ")\\s*(?:\\|[^{}]*)?\\}\\}",
                Pattern.CASE_INSENSITIVE);
    }

    private static String displayOnlyNames() {
        StringBuilder names = new StringBuilder();
        for (String name : DISPLAY_ONLY_TEMPLATES) {
            if (names.length() > 0) {
                names.append('|');
            }
            names.append(quoteWords(name));
        }
        return names.toString();
    }

    /**
     * {@code Peak game} → {@code Peak\s+game}；其余字符一律字面化。
     *
     * 直接 {@code Pattern.quote} 整个名字会把想表达的 {@code \s+} 也字面化，
     * 那样带空格的名字（Peak game / morale boost）就永远匹配不上。
     */
    private static String quoteWords(String name) {
        StringBuilder quoted = new StringBuilder();
        for (String word : name.split(" ")) {
            if (quoted.length() > 0) {
                quoted.append("\\s+");
            }
            quoted.append(Pattern.quote(word));
        }
        return quoted.toString();
    }

    /** 表格与 HTML 注释：整块丢弃 */
    private static final Pattern DROP_BLOCK = Pattern.compile("(?s)\\{\\|.*?\\|\\}|<!--.*?-->");

    /** 剥完链接后可能残留的方括号 / 花括号 */
    private static final Pattern RESIDUAL_MARKUP = Pattern.compile("[\\[\\]{}]");

    /**
     * 粗体/斜体的撇号标记：{@code '''粗'''} 与 {@code ''斜''}。
     *
     * <p><b>为什么不是简单地把 {@code ''} 全删。</b>
     * MediaWiki 用 3 个撇号表示粗体、2 个表示斜体。只删 2 个的写法会让
     * {@code '''Source'''} 变成 {@code 'Source'}（残留单撇号），
     * 实测 Hot Dog 的 List-notes 里正是这种写法。所以先收 3 个、再收 2 个，
     * 单独 1 个的撇号本来就只是普通标点（如 {@code Scout's}），不动它。
     */
    private static final Pattern BOLD = Pattern.compile("'''");
    private static final Pattern ITALIC = Pattern.compile("''");

    /** 模板名的占位符：{@code {{PAGENAME}}} / {{DISPLAYTITLE}} 在页面渲染时就是条目名 */
    private static final Pattern PAGE_NAME_TEMPLATE = Pattern.compile("\\{\\{\\s*pagename\\s*\\}\\}", Pattern.CASE_INSENSITIVE);

    private WikitextUtil() {
    }

    /**
     * 剥掉取值里的 wikitext 标记。
     *
     * @return 剥好的纯文本;入参为 null 时返回 null
     */
    public static String stripMarkup(String raw) {
        return stripMarkup(raw, null);
    }

    /**
     * 剥掉取值里的 wikitext 标记，并把 {@code {{PAGENAME}}} 还原成条目名。
     *
     * <p>长文本调用这个重载：页面散文里到处是 {@code '''{{PAGENAME}}'''} 这种写法，
     * 剥离后若留下字面量 "PAGENAME"，description 会读成"The PAGENAME is a natural food"
     * —— 用户看到的是一个模板占位符，而不是物品名。
     *
     * <p><b>替换顺序是 load-bearing 的</b>，下面每一步的顺序都有理由，改动前先看注释：
     * 先"整块丢弃"后"局部替换"（丢弃类规则要拿到完整原文才能匹配，先删别的会切碎它们），
     * 图片链接先于普通链接，{@code FIXED_TEXT} 先于 {@code DISPLAY_ONLY_NO_ARG}
     * （否则 {@code {{Peak game}}} 会被当成无参模板删空、句子留窟窿），
     * 粗体先于斜体（反之 {@code '''X'''} 会残留单撇号）。
     *
     * @param pageName 条目英文名;为 null 时 {@code {{PAGENAME}}} 剥成空串（不留占位符）
     */
    public static String stripMarkup(String raw, String pageName) {
        if (raw == null) {
            return null;
        }
        String replaced = PAGE_NAME_TEMPLATE.matcher(raw)
                .replaceAll(Matcher.quoteReplacement(pageName == null ? "" : pageName));

        String stripped = DROP_BLOCK.matcher(replaced).replaceAll("");
        stripped = DROP_ELEMENT.matcher(stripped).replaceAll("");
        // 图片链接先整块删,再处理普通链接({{File:…}} 的说明文字不该进正文)
        stripped = MEDIA_LINK.matcher(stripped).replaceAll("");
        // 带竖线 → 显示文本($2);不带竖线 → 目标本身($3)
        stripped = LINK.matcher(stripped).replaceAll("$2$3");
        stripped = EXTERNAL_LINK.matcher(stripped).replaceAll("$1");
        // 排版类模板：带参数的取位置参数（{{Item|Marshmallow}} → Marshmallow），
        // 不带参数的整块丢掉（{{Peak game}}），剩下的交给通用规则只留模板名
        stripped = DISPLAY_ONLY_FIRST_ARG.matcher(stripped).replaceAll("$1");
        // 渲染成固定文字的模板({{Peak game}} → PEAK)必须先于"整块丢掉"那一轮,
        // 否则会被当成无参排版模板直接删掉、句子留窟窿("in  found near")
        stripped = FIXED_TEXT.matcher(stripped).replaceAll(match ->
                Matcher.quoteReplacement(FIXED_TEXT_TEMPLATES.getOrDefault(
                        match.group(1).trim().toLowerCase(Locale.ROOT), "")));
        stripped = DISPLAY_ONLY_NO_ARG.matcher(stripped).replaceAll("");
        stripped = DISPLAY_ONLY.matcher(stripped).replaceAll("");
        // 徽章引用整块删:名字已进 achievement 列,正文里留残缺模板名只会读成半截话。
        // 连带把前面悬空的冠词/介词收掉 —— 否则会读成 "grants the player the."
        stripped = BADGE_TEMPLATE.matcher(stripped).replaceAll("");
        stripped = DANGLING_ARTICLE.matcher(stripped).replaceAll(" ");
        stripped = TEMPLATE.matcher(stripped).replaceAll("$1");
        stripped = TAG.matcher(stripped).replaceAll("");
        stripped = BOLD.matcher(stripped).replaceAll("");
        stripped = ITALIC.matcher(stripped).replaceAll("");
        stripped = RESIDUAL_MARKUP.matcher(stripped).replaceAll("");
        // 删掉被整块丢掉的链接/模板留下的空位(如 "。 ." / "the ." / 连续空格)
        stripped = EMPTY_SLOT.matcher(stripped).replaceAll("$1");
        stripped = DANGLING_ARTICLE.matcher(stripped).replaceAll(" ");
        return stripped.trim();
    }

    /**
     * 被整块删除的内容之前悬空的冠词/介词。
     *
     * 实测 {@code "grants the player the {{Badge|Competitive Eating}}."} 删掉模板后
     * 会读成 "grants the player the."；同类的还有 "instead of a {{Item|…}}" 删成 "instead of a ."。
     * 只在句末/标点前者之间收，避免误伤正常句子里的 "the"（后面跟着实词时不动：
     * {@code (?=[.,;\s]|$)} 要求冠词之后就是标点或结尾）。
     */
    private static final Pattern DANGLING_ARTICLE =
            Pattern.compile("\\s+\\b(?:the|a|an|of|in|to)\\b\\s*(?=[.,;]|$)", Pattern.CASE_INSENSITIVE);

    /**
     * 整块内容被删掉后留下的空位：句末孤零零的句点、行内多余空格、行首的句点。
     *
     * 实测来源是 {@code [[File:…]]} 与 {@code {{Badge|…}}} 整块删空后
     * 剩下的 " . " 与 "the ."。不清掉的话 description 会以"…found in PEAK. ."
     * 这种断句结尾。
     */
    private static final Pattern EMPTY_SLOT =
            Pattern.compile("\\s{2,}|\\s+([.,;:])|^\\s*([.,;:])\\s*");
}

