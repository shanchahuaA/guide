package org.example.guide.controller;

import org.example.guide.pojo.User;
import org.example.guide.pojo.dto.ProfileDto;
import org.example.guide.service.IUserService;
import org.example.guide.service.UserLevels;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 教学的身份与演示后门（契约 §7.0 / §7.1 / §7.6）。本单只落这两个端点。
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

    /** 身份条与分段门禁都靠它。本单里 streak 恒 0、hasApiKey 恒 false（真值，不是占位） */
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
                false);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
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
