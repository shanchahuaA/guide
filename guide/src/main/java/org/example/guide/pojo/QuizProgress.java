package org.example.guide.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * 一个用户的连对进度（#42）：当前连对数 + 本周期内已答对的题号。
 *
 * <p>两者放**同一个** Redis key（{@code cache/QuizProgressCache}）、TTL 2 小时 ——
 * 它们是同一份状态的两半：答错会同时清零并把排除集清空，拆成两个 key 就会出现
 * "连对清了、排除集还在"的中间态。
 *
 * <p>排除集的用途是"当前连对周期内已答对的题不再出"。周期结束（答错、或满 10 升级）
 * 时它跟着 {@link #streak} 一起清零，于是之前答对的题重新出现。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizProgress {

    /** 当前连对数 */
    private int streak;

    /** 本周期内已答对的题号（{@link QuizQuestion#getId()}） */
    private Set<Integer> answeredIds;

    /** 新周期 / 没有缓存时的空进度 */
    public static QuizProgress empty() {
        return new QuizProgress(0, new HashSet<>());
    }

    /** 排除集的可空读法：缓存里缺字段时当空集，不把调用方拖进 null 判断 */
    public Set<Integer> answeredIdsOrEmpty() {
        return answeredIds == null ? Set.of() : answeredIds;
    }
}
