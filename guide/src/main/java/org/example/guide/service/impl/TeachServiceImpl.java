package org.example.guide.service.impl;

import org.example.guide.ai.AiException;
import org.example.guide.ai.DeepSeekClient;
import org.example.guide.ai.ItemContextBuilder;
import org.example.guide.cache.AnswerCache;
import org.example.guide.cache.ItemCache;
import org.example.guide.pojo.User;
import org.example.guide.pojo.dto.AnswerDto;
import org.example.guide.service.ITeachService;
import org.example.guide.service.IUserService;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 图鉴问答的编排（契约 §7.4）。
 *
 * <p>一条问答的路径：**回答缓存 → 用户 Key → 图鉴缓存取上下文 → DeepSeek → 回填缓存**。
 * 顺序是有意的：缓存放在最前面，命中时连 Key 在不在都不必看 ——
 * 同一个问题问第二次不该因为"用户把 Key 删了"而失败，答案本来就与 Key 无关。
 *
 * <p>本单**只做图鉴内问题**。越级门禁（#43）与路线类链接（#44）是后面两条窄切片，
 * 所以这里没有等级参数、也没有 {@code links} 的来源，{@code links} 恒为 {@code []}。
 */
@Service
public class TeachServiceImpl implements ITeachService {

    private static final Logger log = LoggerFactory.getLogger(TeachServiceImpl.class);

    /** 契约 §7.0：{@code ai.enabled = false} 时返回的预置文案 */
    static final String DISABLED_ANSWER = "AI 老师暂时休息中，稍后再来问吧。图鉴里能查到的数据照样看得到。";

    /** 系统提示词。本单没有等级约束（归 #43），只有"以图鉴为准、不编"这条硬边界 */
    private static final String SYSTEM_PROMPT = """
            你是 PEAK 游戏的图鉴助手，只回答与 PEAK 游戏物品有关的问题。

            规则：
            1. 只依据下面提供的图鉴数据回答。数据里没有的，直接说不知道，不要凭印象补充。
            2. 数值（饱食、加成、持续秒数、重量）必须与数据一致，不要四舍五入到别的数。
            3. 用户用中文提问就用中文回答，简洁、口语化，不要罗列原始字段名。
            4. 答不出时给一句"图鉴里没有这条"，不要编造物品名。
            """;

    private final IUserService userService;
    private final ItemCache itemCache;
    private final AnswerCache answerCache;
    private final DeepSeekClient deepSeekClient;

    /** 契约 §7.0 的降级开关。读成字段而不是每次注入 —— 它是启动期配置，不热更 */
    private final boolean enabled;

    public TeachServiceImpl(IUserService userService,
                            ItemCache itemCache,
                            AnswerCache answerCache,
                            DeepSeekClient deepSeekClient,
                            @Value("${guide.ai.enabled:true}") boolean enabled) {
        this.userService = userService;
        this.itemCache = itemCache;
        this.answerCache = answerCache;
        this.deepSeekClient = deepSeekClient;
        this.enabled = enabled;
    }

    @Override
    public BaseResult ask(String openid, String question) {
        if (question == null || question.isBlank()) {
            return fail("请输入你想问的问题");
        }

        // 回答缓存：key 用问题文本（契约 §7.4）。命中就直接回，不碰大模型
        String cached = answerCache.get(question);
        if (cached != null) {
            return success(cached);
        }

        // 降级：不开 AI 时给预置文案而不是报错（契约 §7.0）。演示叙事不变
        if (!enabled) {
            return success(DISABLED_ANSWER);
        }

        User user = userService.findByOpenid(openid);
        if (user == null) {
            return fail("请先登录");
        }
        if (user.getApiKey() == null || user.getApiKey().isBlank()) {
            return fail("请先填写你的 DeepSeek API Key");
        }

        // 上下文取自图鉴缓存、不直查库（票面口径）；它一旦为空就是图鉴本身不可用，
        // 这时调大模型只会得到一段编出来的话，不如直接说取不到
        var items = itemCache.getAll();
        if (items.isEmpty()) {
            return fail("图鉴数据还取不到，请稍后再试");
        }
        String context = ItemContextBuilder.build(items, question);

        String answer;
        try {
            answer = deepSeekClient.chat(user.getApiKey(), SYSTEM_PROMPT, context + "\n\n用户的问题：" + question);
        } catch (AiException e) {
            // 错误码映射已经在客户端那一处做完，这里只负责把它塞进统一响应包（契约 §7.5）
            return fail(e.getMessage());
        }

        answerCache.put(question, answer);
        return success(answer);
    }

    /** {@code data} 的形状见契约 §7.4：{@code answer} + {@code links}（本单恒空数组） */
    private BaseResult success(String answer) {
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, new AnswerDto(answer).toMap());
    }

    private BaseResult fail(String message) {
        BaseResult result = BaseResult.setResult(ResultCodeEnum.FAILURE, null);
        result.setMessage(message);
        return result;
    }
}
