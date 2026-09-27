package org.example.guide.service;

import org.example.guide.pojo.QuizProgress;

import java.util.HashSet;
import java.util.Set;

/**
 * 连对状态机（#42）。纯函数、无 Spring、无 Redis —— 单测直接跑，四条路径各钉一条。
 *
 * <p>规则（票面）：
 * <ul>
 *   <li><b>答对</b>：连对 +1，该题进排除集（本周期内不再出）；</li>
 *   <li><b>答错</b>：连对归零，排除集**一并清空**（之前答对的题重新出现）；</li>
 *   <li><b>连对满 10</b>：升级、连对与排除集归零；</li>
 *   <li><b>已是高手</b>：满 10 也不再升，但连对/排除集同样归零（否则排除集攒满 30 题后无题可出）。</li>
 * </ul>
 *
 * <p>等级封顶由 {@link UserLevels#nextLevel} 保证，这里不重复写那个上限。
 */
public final class QuizStreak {

    /** 连对多少题升级（CONTEXT.md「用户等级」与契约 §7.1 的 streakTarget） */
    public static final int TARGET = 10;

    private QuizStreak() {
    }

    /**
     * 一次作答的结果。
     *
     * @param correct     这一题是否答对
     * @param levelUp     本次是否升级（已是高手时为 false）
     * @param level       作答后的等级
     * @param streak      作答后的连对数
     * @param answeredIds 作答后的排除集
     */
    public record Outcome(boolean correct, boolean levelUp, int level, int streak, Set<Integer> answeredIds) {
    }

    /**
     * 推进一步。
     *
     * @param correct    这一题是否答对
     * @param level      作答前的等级
     * @param answeredId 这一题的题号（答对时进排除集）
     * @param current    作答前的进度。调用方拿的是 {@code QuizProgressCache.get} 的结果，**必定非空**
     *                   （没有缓存时它返回 {@link QuizProgress#empty()}），所以这里不判 null
     */
    public static Outcome advance(boolean correct, int level, int answeredId, QuizProgress current) {
        if (!correct) {
            return new Outcome(false, false, level, 0, Set.of());
        }

        int streak = current.getStreak() + 1;
        if (streak >= TARGET) {
            int nextLevel = UserLevels.nextLevel(level);
            return new Outcome(true, nextLevel > level, nextLevel, 0, Set.of());
        }

        Set<Integer> answered = new HashSet<>(current.answeredIdsOrEmpty());
        answered.add(answeredId);
        return new Outcome(true, false, level, streak, answered);
    }
}
