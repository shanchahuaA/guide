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
import org.example.guide.service.TeachGate;
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
 * <p>图鉴内问题走"缓存 → Key → 图鉴上下文 → DeepSeek"；**路线类问题**（#44）不走这条，
 * 只把 {@link BilibiliRouteService} 构造好的链接回给前端，{@code links} 非空。
 *
 * <p><b>等级约束（#43）分两层</b>：**硬拦在路由层**（{@code TeachGate}）—— 拦下的请求根本到不了
 * 这里，也就不查缓存、不调大模型；到了这里的请求，等级只用来把边界注入系统提示词（软约束），
 * 管住回答的深度与口吻。硬拦不放这一层的理由见 {@code TeachController.ask}：回答缓存的 key
 * 只认问题文本，若先查缓存，一个高手问过的越级问题会把缓存里的答案漏给随后提问的菜鸟。
 */
@Service
public class TeachServiceImpl implements ITeachService {

    private static final Logger log = LoggerFactory.getLogger(TeachServiceImpl.class);

    /** 契约 §7.0：{@code ai.enabled = false} 时返回的预置文案 */
    static final String DISABLED_ANSWER = "AI 老师暂时休息中，稍后再来问吧。图鉴里能查到的数据照样看得到。";

    /** 路线类问题的引导语。链接本身由 {@link BilibiliRouteService} 构造（#44） */
    static final String ROUTE_ANSWER = "这是今天的 B站 攻略链接，点开来看看：";

    /** 系统提示词的固定部分：以图鉴为准、不编。等级边界由 {@link TeachGate#boundaryFor} 追加在后 */
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
    private final BilibiliRouteService routeService;

    /** 契约 §7.0 的降级开关。读成字段而不是每次注入 —— 它是启动期配置，不热更 */
    private final boolean enabled;

    public TeachServiceImpl(IUserService userService,
                            ItemCache itemCache,
                            AnswerCache answerCache,
                            DeepSeekClient deepSeekClient,
                            BilibiliRouteService routeService,
                            @Value("${guide.ai.enabled:true}") boolean enabled) {
        this.userService = userService;
        this.itemCache = itemCache;
        this.answerCache = answerCache;
        this.deepSeekClient = deepSeekClient;
        this.routeService = routeService;
        this.enabled = enabled;
    }

    @Override
    public BaseResult ask(String openid, String question, Integer level) {
        if (question == null || question.isBlank()) {
            return fail("请输入你想问的问题");
        }

        // 路线类（#44）：链接由后端构造，**不调大模型** —— 模型不能联网（CONTEXT.md「每日路线」），
        // 更不需要用户的 Key。放在回答缓存与降级开关之前有两个理由：
        //   1. 链接里带**当天日期**，缓存到明天就是过期链接，所以不缓存；
        //   2. 它压根不走大模型，guide.ai.enabled 这个开关管不着它。
        // 越级问题到不了这里 —— 控制器已按等级硬拦（TeachGate）。
        if (TeachGate.classify(question) == TeachGate.Category.ROUTE) {
            return routeAnswer();
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

        // 软约束：把同一份等级边界注入系统提示词（契约 §7.0 的"双保险"，硬拦那一半在路由层）
        String systemPrompt = SYSTEM_PROMPT + "\n" + TeachGate.boundaryFor(level);

        String answer;
        try {
            answer = deepSeekClient.chat(user.getApiKey(), systemPrompt, context + "\n\n用户的问题：" + question);
        } catch (AiException e) {
            // 错误码映射已经在客户端那一处做完，这里只负责把它塞进统一响应包（契约 §7.5）
            return fail(e.getMessage());
        }

        answerCache.put(question, answer);
        return success(answer);
    }

    @Override
    public BaseResult route() {
        // 分级入口 /api/teach/beginner/route 专用。等级门禁在路由层的 Shiro 路径规则上
        // （beginner/** 要 beginner），这里只负责把链接装进响应；不调大模型、不进缓存。
        return routeAnswer();
    }

    /** 路线类问题的回答：引导语 + 后端构造的 B站 链接（契约 §7.4，{@code links} 非空） */
    private BaseResult routeAnswer() {
        return BaseResult.setResult(ResultCodeEnum.SUCCESS,
                new AnswerDto(ROUTE_ANSWER, routeService.links()).toMap());
    }

    /** {@code data} 的形状见契约 §7.4：{@code answer} + {@code links}（图鉴内问题恒空数组） */
    private BaseResult success(String answer) {
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, new AnswerDto(answer).toMap());
    }

    private BaseResult fail(String message) {
        BaseResult result = BaseResult.setResult(ResultCodeEnum.FAILURE, null);
        result.setMessage(message);
        return result;
    }
}
