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
     * 抽一道题。
     *
     * <p>题库懒生成：该级题库不在缓存里时才调大模型生成并落缓存。
     * 响应**只有题号 / 题干 / 选项**，不含答案。
     *
     * @param bankLevel 想练的题库档位（0/1/2）。**只能 ≤ 自己的等级**（等级单调包含：高手能回头练低级题，
     *                  低级碰不到高级）；传 {@code null} 表示"就练我自己这档"。档位高于自己等级 → {@code 403}
     */
    BaseResult next(String openid, Integer bankLevel);

    /**
     * 判题并推进连对状态。对错由后端比对下标，不交给大模型。
     *
     * @param bankLevel  这一题属于哪一档题库（与抽题时传的同一个值）。换档会从零开始 ——
     *                   题号是题库内的序号，跨档会重号，不能拿旧排除集去过滤新档
     * @param questionId 抽题时下发的题号（契约 §7.3 的 {@code questionId}）
     * @param choice     用户选择的下标（0-3，契约 §7.3 的 {@code choice}）
     */
    BaseResult answer(String openid, Integer bankLevel, Integer questionId, Integer choice);

    /** 当前连对数，给身份条用；没有进度时返回 0 */
    int streakOf(String openid);

    /**
     * 重置练习进度：连对与排除集归零，之后重新抽题。
     *
     * <p>两个调用点：**练习面板的「重置」按钮**（用户主动重开），以及**跳级换档后**
     * （等级变了题库也换了，排除集里是旧题库的题号）。不做"只清 UI 不动进度"的轻量版 ——
     * 用户按的就是"重新开始"。
     */
    BaseResult reset(String openid);
}
