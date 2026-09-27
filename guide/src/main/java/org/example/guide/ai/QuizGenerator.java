package org.example.guide.ai;

import org.example.guide.pojo.Item;
import org.example.guide.pojo.QuizQuestion;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 出题：把全量图鉴喂给大模型，换回一批结构化的四选一题目（#42）。
 *
 * <p><b>全量一次喂进去</b>（{@link ItemContextBuilder#buildAll}），不做关键词筛选 ——
 * 出题素材越多，越不容易反复问同几个物品。
 *
 * <p><b>只用 JSON 模式</b>（{@link DeepSeekClient#chatJson}）：题干 / 四选项 / 正确项 / 解析
 * 是一段结构，自然语言答案没法解析。
 *
 * <p><b>解析失败要重试</b>：模型偶发会吐一段带 markdown 围栏、或字段缺失的文字，这时重调一次
 * 比把半个题库写进缓存强。**只重试"解析失败"这一种** —— Key 无效 / 网络超时这类
 * {@link AiException} 直接冒出去，再调一次只是多浪费一次额度。
 */
@Component
public class QuizGenerator {

    /** 每级题目数（票面口径）：向模型要的数量 */
    static final int QUESTIONS_PER_LEVEL = 30;

    /**
     * 题库**至少**要有的题数。这不是"向模型要多少"（那是 {@link #QUESTIONS_PER_LEVEL}），
     * 而是"少于这个数就没法用"：连对 10 题升一级，题库短于 10 时一个周期都走不完；
     * 而"本周期内已答对的题不再出"又要求周期内不重复出题 —— 两者叠加，短题库会让抽题无解。
     * 所以解析出来少于它就直接当这次生成失败、走重试，绝不把短题库写进缓存。
     *
     * <p>数值与 {@code QuizStreak.TARGET} 一致，但**不在 ai 包里反向依赖 service 包**去引用它
     * （那会让 ai 与 service 互相依赖），所以这里保留一个字面量并在两处注释里对齐。
     */
    static final int MIN_QUESTIONS = 10;

    /** 四选一 */
    private static final int OPTION_COUNT = 4;

    /** 最多调几次。首次 + 两次重试 */
    static final int MAX_ATTEMPTS = 3;

    /**
     * 系统提示词。**必须出现 "json" 字样** —— DeepSeek 的 {@code json_object} 模式会校验这一点，
     * 提示词里没有 json 时请求会被直接拒掉。
     */
    private static final String SYSTEM_PROMPT = """
            你是 PEAK 游戏的出题老师，负责根据给定的图鉴数据出题，只返回 json，不要任何多余文字。

            要求：
            1. 题量以用户要求为准，一次至少 10 道，尽量凑够要求的数量。
            2. 题目必须能在给定数据里找到依据，不要编造物品或数值。
            3. 每题给一个题干、恰好四个选项、一个正确项下标（0 到 3）、一段解析。
            4. 四个选项要似是而非，干扰项可以取同类的另一个物品或相近的数值。
            5. 解析简短点题即可，说明依据。

            返回的 json 结构（严格遵守）：
            {"questions":[{"stem":"题干","options":["A","B","C","D"],"answerIndex":0,"explanation":"解析"}]}
            """;

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public QuizGenerator(DeepSeekClient deepSeekClient, ObjectMapper objectMapper) {
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成某一级的题库。
     *
     * @param apiKey 用户自己的 DeepSeek Key（服务端不持有 Key）
     * @param items  全量图鉴条目（来自图鉴缓存）
     * @param level  等级，仅用于提示词里的一句上下文
     * @return 解析出的题目，题号按顺序重新编号（丢弃解析不了的题目后从 0 连续）；数量不少于 {@link #MIN_QUESTIONS}
     * @throws AiException 调用失败（不重试），或重试 {@link #MAX_ATTEMPTS} 次仍解析不出够用的题目
     */
    public List<QuizQuestion> generate(String apiKey, List<Item> items, int level) {
        String context = ItemContextBuilder.buildAll(items);
        String userPrompt = "请根据下面的图鉴数据出 " + QUESTIONS_PER_LEVEL + " 道题（面向等级 " + level + " 的玩家）：\n\n" + context;

        AiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // 调用的 AiException 不 catch，直接冒出去：Key 无效重试也是无效
            String raw = deepSeekClient.chatJson(apiKey, SYSTEM_PROMPT, userPrompt);
            try {
                List<QuizQuestion> questions = parse(raw);
                // 题数不够也当失败重试：短题库会让"已答对的题不再出"在一个周期内无题可出
                if (questions.size() < MIN_QUESTIONS) {
                    last = new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 出的题太少，请重试", null);
                    continue;
                }
                return questions;
            } catch (AiException e) {
                last = e;
            }
        }
        throw last;
    }

    /**
     * 把模型返回的文本解析成题目。**包级可见是为了单测** —— 单测用假的响应文本钉住
     * "结构校验 + 重编号"，不真调 DeepSeek。
     *
     * <p>单题不合结构就跳过（不整批失败），但**一条都没剩下**时抛异常触发重试。
     */
    List<QuizQuestion> parse(String raw) {
        JsonNode array = questionsArray(readTree(raw));

        List<QuizQuestion> questions = new ArrayList<>();
        for (JsonNode node : array) {
            QuizQuestion question = toQuestion(node);
            if (question != null) {
                // 题号只是"本题库内的序号"，丢弃坏题后必须重编号，否则会出现空洞、也可能撞号
                question.setId(questions.size());
                questions.add(question);
            }
        }
        if (questions.isEmpty()) {
            throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 出的题不符合结构，请重试", null);
        }
        return questions;
    }

    private JsonNode readTree(String raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 返回的题目不是合法 JSON，请重试", e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 返回的题目是空的，请重试", null);
        }
        return root;
    }

    /** 兼容两种形态：严格按提示词给的对象 {@code {"questions":[...]}}，以及模型偶尔直接给数组 */
    private static JsonNode questionsArray(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        JsonNode questions = root.path("questions");
        if (questions.isArray()) {
            return questions;
        }
        throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 返回的题目缺少 questions 数组，请重试", null);
    }

    /** 单题转模型；任一处不合结构返回 null，由 {@link #parse} 跳过 */
    private static QuizQuestion toQuestion(JsonNode node) {
        String stem = node.path("stem").asString("").trim();
        if (stem.isEmpty()) {
            return null;
        }

        JsonNode optionsNode = node.path("options");
        if (!optionsNode.isArray()) {
            return null;
        }
        List<String> options = new ArrayList<>();
        for (JsonNode option : optionsNode) {
            options.add(option.asString(""));
        }
        if (options.size() != OPTION_COUNT) {
            return null;
        }

        JsonNode answerNode = node.path("answerIndex");
        if (!answerNode.isNumber()) {
            return null;
        }
        int answerIndex = answerNode.asInt();
        if (answerIndex < 0 || answerIndex >= OPTION_COUNT) {
            return null;
        }

        return new QuizQuestion(null, stem, options, answerIndex, node.path("explanation").asString(""));
    }
}
