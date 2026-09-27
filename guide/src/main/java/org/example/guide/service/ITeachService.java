package org.example.guide.service;

import org.example.guide.utils.BaseResult;

/**
 * AI 教学（契约 §7）。本单（#41）只落图鉴问答这一条路径。
 */
public interface ITeachService {

    /**
     * 图鉴内问答：先看回答缓存，未命中才调 DeepSeek。
     *
     * @param openid   当前用户（token 即 openid）
     * @param question 用户原问题
     * @return 成功时 {@code data.answer} 是答案（{@code links} 本单恒为空数组）；
     *         失败时是 {@code -100} + 中文提示，**不是 500**（契约 §7.5）
     */
    BaseResult ask(String openid, String question);
}
