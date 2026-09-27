package org.example.guide.service;

import org.example.guide.utils.BaseResult;

/**
 * AI 教学的「练习」（#42）：抽题、判题、连对与升级。
 *
 * <p>与 {@link ITeachService}（问答）分开：问答是"问一句话拿答案"，练习是"抽题-作答-改状态"，
 * 两者的输入输出与状态都不同，合成一个接口只会让两边的方法都变胖。
 */
public interface IQuizService {

    /**
     * 按用户当前等级抽一道题。
     *
     * <p>题库懒生成：该级题库不在缓存里时才调大模型生成并落缓存。
     * 响应**只有题号 / 题干 / 选项**，不含答案。
     */
    BaseResult next(String openid);

    /**
     * 判题并推进连对状态。对错由后端比对下标，不交给大模型。
     *
     * @param questionId 抽题时下发的题号（契约 §7.3 的 {@code questionId}）
     * @param choice     用户选择的下标（0-3，契约 §7.3 的 {@code choice}）
     */
    BaseResult answer(String openid, Integer questionId, Integer choice);

    /** 当前连对数，给身份条用；没有进度时返回 0 */
    int streakOf(String openid);
}
