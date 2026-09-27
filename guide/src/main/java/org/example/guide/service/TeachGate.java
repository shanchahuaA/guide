package org.example.guide.service;

import java.util.List;
import java.util.Locale;

/**
 * AI 老师的越级门禁（#43）：按用户等级约束它能回答什么。
 *
 * <p>同一份等级边界在这里定义两遍，是票面要的**双保险**：
 * <ul>
 *   <li>{@link #blocked} —— 硬拦。拦下的请求在**路由层**就被挡回，不查缓存、不调大模型；</li>
 *   <li>{@link #boundaryFor} —— 软约束。把同一份边界注入系统提示词，管住回答的深度与口吻。</li>
 * </ul>
 * 两者必须同时改：只改代码会把被拦的话题从提示词那边漏出去，只改提示词则拦不住。
 *
 * <p>门禁矩阵（票面）：
 * <pre>
 *   等级 0 菜鸟：禁 路线类 + 速通类
 *   等级 1 入门：禁 速通类
 *   等级 2 高手：无
 * </pre>
 *
 * <p>纯函数，无 Spring、无库 —— 单测直接跑。
 */
public final class TeachGate {

    /** 越级问题的类别。{@code NONE} 是普通图鉴内问题，任何等级都不拦 */
    public enum Category {
        NONE, ROUTE, SPEEDRUN
    }

    /**
     * 路线类关键词（票面举例「今日最佳路线」）。
     *
     * <p>收英文是因为用户可能直接打 {@code route}。**不把"走哪"这类词算进来** ——
     * 它更常出现在图鉴内问法里（「这蘑菇走哪刷」问的是生态），收了会把菜鸟的正常问题也拦掉。
     */
    private static final List<String> ROUTE_WORDS = List.of("路线", "怎么走", "route");

    /** 速通类关键词（票面举例「怎么速通」） */
    private static final List<String> SPEEDRUN_WORDS = List.of("速通", "快速通关", "最快通关", "speedrun");

    private TeachGate() {
    }

    /**
     * 给问题归类。
     *
     * <p><b>速通优先于路线</b>：一句话里两类词都出现时（「速通的最佳路线」）按更严的那类算。
     * 反过来的话，入门用户（只禁速通）会因为"先判路线、路线放行"而漏过唯一的限制。
     */
    public static Category classify(String question) {
        if (question == null || question.isBlank()) {
            return Category.NONE;
        }
        String lowered = question.toLowerCase(Locale.ROOT);
        if (containsAny(lowered, SPEEDRUN_WORDS)) {
            return Category.SPEEDRUN;
        }
        if (containsAny(lowered, ROUTE_WORDS)) {
            return Category.ROUTE;
        }
        return Category.NONE;
    }

    /**
     * 该等级能不能问该类问题。
     *
     * <p>越界的低值（负数）按菜鸟算 —— 门禁是"拦"的功能，宁可多拦也不要漏
     * （与 {@link UserLevels#nameOf} 的兜底同一取向）。
     */
    public static boolean blocked(Integer level, Category category) {
        if (category == null || category == Category.NONE) {
            return false;
        }
        int lv = level == null ? UserLevels.NOVICE : level;
        if (lv <= UserLevels.NOVICE) {
            return true;
        }
        if (lv == UserLevels.BEGINNER) {
            return category == Category.SPEEDRUN;
        }
        return false;
    }

    /**
     * 拦下时回给小程序的那句话。
     *
     * <p>**按等级分支、只有两句**，且入门那句绝不能出现"新手" ——
     * 同一句套两级会把入门用户叫成新手（票面明写）。
     * 只有 {@link #blocked} 为真时才会调它（高手拦不着），所以高手没有对应文案。
     */
    public static String message(Integer level) {
        int lv = level == null ? UserLevels.NOVICE : level;
        if (lv <= UserLevels.NOVICE) {
            return "这个对新手还是太难了，等成为高手再来吧！";
        }
        return "速通的事等你成为高手再聊吧！";
    }

    /**
     * 同一份等级边界的**散文版**，由 {@code TeachServiceImpl} 拼进系统提示词（票面「双保险」）。
     *
     * <p>等级名一律取自 {@link UserLevels}，别在这里另写一套叫法。
     */
    public static String boundaryFor(Integer level) {
        int lv = level == null ? UserLevels.NOVICE : level;
        if (lv <= UserLevels.NOVICE) {
            return "当前用户是" + UserLevels.nameOf(lv) + "（等级 0/2）：不要回答路线类问题（如「今日最佳路线」）"
                    + "与速通类问题（如「怎么速通」）——这对他还太难，请他成为高手后再来问。";
        }
        if (lv == UserLevels.BEGINNER) {
            return "当前用户是入门（等级 1/2）：不要回答速通类问题（如「怎么速通」），"
                    + "请他成为高手后再聊；路线类问题可以正常回答。";
        }
        return "当前用户是高手（等级 2/2）：没有等级限制，路线类与速通类问题都可以回答。";
    }

    private static boolean containsAny(String loweredQuestion, List<String> words) {
        for (String word : words) {
            if (loweredQuestion.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
