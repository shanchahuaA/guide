package org.example.guide.controller;

import org.example.guide.pojo.User;
import org.example.guide.pojo.dto.ProfileDto;
import org.example.guide.service.ITeachService;
import org.example.guide.service.IUserService;
import org.example.guide.service.UserLevels;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 教学的身份与问答（契约 §7.0 / §7.1 / §7.2 / §7.4 / §7.6）。
 *
 * <p><b>鉴权是手工的</b>：Shiro 过滤链仍是 {@code /** = anon}（收口归 #5），
 * 这里直接校验请求头 {@code token}（占位串，值即 openid）。缺 token 返
 * {@code code = 401}、HTTP 状态仍是 200 —— 前端靠响应包里的 code 判未登录。
 */
@RestController
public class TeachController {

    /** 连对 10 题升级（CONTEXT.md「用户等级」） */
    private static final int STREAK_TARGET = 10;

    @Autowired
    private IUserService userService;

    @Autowired
    private ITeachService teachService;

    /** 身份条与分段门禁都靠它。本单里 streak 恒 0（连对计数归 #42） */
    @GetMapping("/api/teach/profile")
    public BaseResult profile(@RequestHeader(value = "token", required = false) String token) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        ProfileDto dto = new ProfileDto(
                user.getLevel(),
                UserLevels.nameOf(user.getLevel()),
                0,
                STREAK_TARGET,
                hasApiKey(user));
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
    }

    /**
     * 收下用户自己的 DeepSeek Key（契约 §7.2）。
     *
     * <p><b>不预校验</b>：写进 {@code user.api_key} 即回 200，Key 有没有效要到真正调用时才知道
     * （错误码见 §7.5）。拿这一条去做一次"试调"看似稳妥，实际是把用户的 Key 多用一次、
     * 还多一个"预校验说有效、真调用说无效"的中间态。
     */
    @PostMapping("/api/teach/apikey")
    public BaseResult saveApiKey(@RequestHeader(value = "token", required = false) String token,
                                 @RequestBody(required = false) Map<String, String> body) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        String apiKey = body == null ? null : body.get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            BaseResult bad = BaseResult.setResult(ResultCodeEnum.PARAM_ERROR, null);
            bad.setMessage("apiKey 不能为空");
            return bad;
        }
        userService.updateApiKey(user.getOpenid(), apiKey);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, null);
    }

    /**
     * 图鉴内问答（契约 §7.4）。入参 {@code {question}}，出参 {@code data.answer}。
     *
     * <p>编排在 {@link ITeachService#ask}：缓存 → Key → 图鉴上下文 → DeepSeek → 回填缓存。
     * 大模型的失败在这里变成 {@code -100} + 中文提示（**不是 500**），映射的唯一一处在
     * {@code ai/DeepSeekClient}。
     */
    @PostMapping("/api/teach/ask")
    public BaseResult ask(@RequestHeader(value = "token", required = false) String token,
                          @RequestBody(required = false) Map<String, String> body) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        String question = body == null ? null : body.get("question");
        return teachService.ask(user.getOpenid(), question);
    }

    /**
     * 演示用跳级后门（契约 §7.6）：当前用户 level + 1，封顶 2。
     *
     * <p>与 {@code UserController.testInsertUser} 同一类东西 —— 演示后门，留着不删。
     */
    @PostMapping("/api/teach/dev/level")
    public BaseResult devLevel(@RequestHeader(value = "token", required = false) String token) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        int next = UserLevels.nextLevel(user.getLevel());
        userService.updateLevel(user.getOpenid(), next);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, Map.of("level", next));
    }

    /** 身份条靠它决定要不要引导用户先填 Key（契约 §7.1） */
    private static boolean hasApiKey(User user) {
        return user.getApiKey() != null && !user.getApiKey().isBlank();
    }

    /** token 即 openid；空 token 或查不到人都当未登录 */
    private User userByToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return userService.findByOpenid(token);
    }

    private BaseResult unauthorized() {
        return BaseResult.setResult(ResultCodeEnum.UNAUTHORIZED, null);
    }
}
