package org.example.guide.service;

import org.example.guide.utils.BaseResult;

/**
 * AI 教学（契约 §7）。本单（#41）只落图鉴问答这一条路径；等级软约束由 #43 接进来。
 */
public interface ITeachService {

    /**
     * 图鉴内问答：先看回答缓存，未命中才调 DeepSeek。
     *
     * @param openid   当前用户（token 即 openid）
     * @param question 用户原问题
     * @param level    用户等级，**只从身份取**（契约 §7.0：端点不接受 level 参数）。
     *                 用来把等级边界注入系统提示词（软约束）；硬拦在**路由层**做，见 {@code TeachGate}
     * @return 成功时 {@code data.answer} 是答案。图鉴内问题的 {@code links} 是空数组；
     *         路线类问题（#44）的 {@code links} 是后端构造的 B站 链接（非空）。
     *         失败时是 {@code -100} + 中文提示，**不是 500**（契约 §7.5）
     */
    BaseResult ask(String openid, String question, Integer level);

    /**
     * 「今日路线」的分级入口（契约 §7.4 / §7.7）：只回引导语 + 后端构造的 B站 链接。
     *
     * <p>**不调大模型、不进回答缓存**（链接里带当天日期，缓存到明天就是过期链接）——
     * 与 {@link #ask} 的路线分支同一份产出，只是入口不同。
     *
     * <p>**等级门禁不在这里** —— 它在路由层的 Shiro 路径规则上：
     * {@code /api/teach/beginner/**} 要 beginner（≥入门），见 {@code config/ShiroConfig}。
     */
    BaseResult route();
}
