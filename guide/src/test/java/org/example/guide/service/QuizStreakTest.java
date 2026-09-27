package org.example.guide.service;

import org.example.guide.pojo.QuizProgress;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连对状态机（#42）的四条路径：对 / 错 / 升级 / 封顶。**纯函数，不加载 Spring、不连 Redis。**
 */
class QuizStreakTest {

    @Test
    void 答对时连对加一且该题进排除集() {
        QuizProgress before = new QuizProgress(3, new HashSet<>(Set.of(1, 2, 3)));

        QuizStreak.Outcome outcome = QuizStreak.advance(true, UserLevels.NOVICE, 5, before);

        assertThat(outcome.correct()).isTrue();
        assertThat(outcome.levelUp()).isFalse();
        assertThat(outcome.level()).isEqualTo(UserLevels.NOVICE);
        assertThat(outcome.streak()).isEqualTo(4);
        assertThat(outcome.answeredIds()).containsExactlyInAnyOrder(1, 2, 3, 5);
    }

    @Test
    void 答错时连对归零且排除集清空() {
        QuizProgress before = new QuizProgress(4, new HashSet<>(Set.of(1, 2, 3, 5)));

        QuizStreak.Outcome outcome = QuizStreak.advance(false, UserLevels.BEGINNER, 7, before);

        assertThat(outcome.correct()).isFalse();
        assertThat(outcome.streak()).isZero();
        // 之前答对的题要重新出现 —— 排除集一并清空
        assertThat(outcome.answeredIds()).isEmpty();
        // 答错不改等级
        assertThat(outcome.level()).isEqualTo(UserLevels.BEGINNER);
    }

    @Test
    void 连对满十升级并清零() {
        QuizProgress before = new QuizProgress(9, new HashSet<>(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9)));

        QuizStreak.Outcome outcome = QuizStreak.advance(true, UserLevels.NOVICE, 10, before);

        assertThat(outcome.correct()).isTrue();
        assertThat(outcome.levelUp()).isTrue();
        assertThat(outcome.level()).isEqualTo(UserLevels.BEGINNER);
        assertThat(outcome.streak()).isZero();
        assertThat(outcome.answeredIds()).isEmpty();
    }

    @Test
    void 已是高手时满十不再升级但连对清零() {
        QuizProgress before = new QuizProgress(9, new HashSet<>(Set.of(1, 2, 3)));

        QuizStreak.Outcome outcome = QuizStreak.advance(true, UserLevels.MAX, 10, before);

        assertThat(outcome.levelUp()).isFalse();
        assertThat(outcome.level()).isEqualTo(UserLevels.MAX);
        // 封顶只挡等级，连对/排除集照样进入新周期，否则排除集会无上限地攒下去
        assertThat(outcome.streak()).isZero();
        assertThat(outcome.answeredIds()).isEmpty();
    }

    @Test
    void 没有进度时从第一题开始() {
        QuizStreak.Outcome outcome = QuizStreak.advance(true, UserLevels.NOVICE, 0, null);

        assertThat(outcome.streak()).isEqualTo(1);
        assertThat(outcome.answeredIds()).containsExactly(0);
    }
}
