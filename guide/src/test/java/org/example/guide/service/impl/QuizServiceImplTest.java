package org.example.guide.service.impl;

import org.example.guide.ai.QuizGenerator;
import org.example.guide.cache.ItemCache;
import org.example.guide.cache.QuizBankCache;
import org.example.guide.cache.QuizProgressCache;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.QuizProgress;
import org.example.guide.pojo.QuizQuestion;
import org.example.guide.pojo.User;
import org.example.guide.service.IUserService;
import org.example.guide.utils.BaseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link QuizServiceImpl} 的编排：抽题不吐答案、排除已答对的题、判题与状态落库落缓存。
 * **不加载 Spring、不连 Redis / MySQL、不真调 DeepSeek。**
 *
 * <p>状态机本身的四条路径在 {@code QuizStreakTest} 里，题库解析与重试在 {@code QuizGeneratorTest} 里；
 * 这里只钉"编排有没有接上"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizServiceImplTest {

    private static final String OPENID = "oTest";

    @Mock
    private IUserService userService;
    @Mock
    private ItemCache itemCache;
    @Mock
    private QuizGenerator quizGenerator;
    @Mock
    private QuizBankCache quizBankCache;
    @Mock
    private QuizProgressCache quizProgressCache;

    private QuizServiceImpl service() {
        return new QuizServiceImpl(userService, itemCache, quizGenerator, quizBankCache, quizProgressCache);
    }

    // ── 抽题 ────────────────────────────────────────────────────────────────

    @Test
    void 抽题响应只有题号题干选项没有答案() {
        givenUser(0, "sk-good");
        when(quizBankCache.get(0)).thenReturn(List.of(question(0, 2)));
        when(quizProgressCache.get(OPENID)).thenReturn(QuizProgress.empty());

        BaseResult result = service().next(OPENID);

        assertThat(result.getCode()).isEqualTo(200);
        // 票面 AC：/quiz/next 的响应里只有题号 / 题干 / 选项三样，没有答案
        assertThat(result.getData()).containsOnlyKeys("id", "stem", "options");
        assertThat(result.getData().get("options")).isEqualTo(List.of("A", "B", "C", "D"));
    }

    @Test
    void 当前周期内已答对的题不再出() {
        givenUser(0, "sk-good");
        when(quizBankCache.get(0)).thenReturn(List.of(question(0, 0), question(1, 1)));
        when(quizProgressCache.get(OPENID)).thenReturn(new QuizProgress(1, new HashSet<>(Set.of(0))));

        // 反复抽，已答对的题号 0 不该再出现
        for (int i = 0; i < 20; i++) {
            assertThat(service().next(OPENID).getData().get("id")).isEqualTo(1);
        }
    }

    @Test
    void 题库缺失时生成并回填() {
        givenUser(0, "sk-good");
        when(quizBankCache.get(0)).thenReturn(null);
        when(itemCache.getAll()).thenReturn(List.of(new Item()));
        when(quizGenerator.generate(eq("sk-good"), anyList(), eq(0))).thenReturn(List.of(question(0, 0)));
        when(quizProgressCache.get(OPENID)).thenReturn(QuizProgress.empty());

        service().next(OPENID);

        // 懒生成落缓存是"第一次有人要某级的题时才生成，之后复用"这条的实现
        verify(quizBankCache).put(eq(0), anyList());
    }

    @Test
    void 没有题库又没有Key时提示先填且不生成() {
        givenUser(0, null);
        when(quizBankCache.get(0)).thenReturn(null);

        BaseResult result = service().next(OPENID);

        assertThat(result.getCode()).isEqualTo(-100);
        assertThat(result.getMessage()).contains("Key");
        verifyNoInteractions(quizGenerator);
    }

    // ── 判题 ────────────────────────────────────────────────────────────────

    @Test
    void 答对时连对加一且写回缓存不升级() {
        givenUser(0, "sk-good");
        when(quizBankCache.get(0)).thenReturn(List.of(question(3, 1)));
        when(quizProgressCache.get(OPENID)).thenReturn(QuizProgress.empty());

        BaseResult result = service().answer(OPENID, 3, 1);

        assertThat(result.getData().get("correct")).isEqualTo(true);
        assertThat(result.getData().get("answerIndex")).isEqualTo(1);
        assertThat(result.getData().get("explanation")).isEqualTo("解析");
        assertThat(result.getData().get("streak")).isEqualTo(1);
        verify(quizProgressCache).put(eq(OPENID), any(QuizProgress.class));
        verify(userService, never()).updateLevel(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 答错时连对归零() {
        givenUser(0, "sk-good");
        when(quizBankCache.get(0)).thenReturn(List.of(question(3, 1)));
        when(quizProgressCache.get(OPENID)).thenReturn(new QuizProgress(5, new HashSet<>(Set.of(1, 2))));

        BaseResult result = service().answer(OPENID, 3, 0);

        assertThat(result.getData().get("correct")).isEqualTo(false);
        assertThat(result.getData().get("streak")).isEqualTo(0);
    }

    @Test
    void 连对满十升级并落库() {
        givenUser(0, "sk-good");
        when(quizBankCache.get(0)).thenReturn(List.of(question(3, 1)));
        when(quizProgressCache.get(OPENID)).thenReturn(new QuizProgress(9, new HashSet<>()));

        BaseResult result = service().answer(OPENID, 3, 1);

        assertThat(result.getData().get("level")).isEqualTo(1);
        assertThat(result.getData().get("streak")).isEqualTo(0);
        assertThat(result.getData().get("levelName")).isEqualTo("入门");
        verify(userService).updateLevel(OPENID, 1);
    }

    @Test
    void 题号对不上题库时提示重新开始且不判题() {
        givenUser(0, "sk-good");
        when(quizBankCache.get(0)).thenReturn(List.of(question(0, 0)));

        BaseResult result = service().answer(OPENID, 99, 0);

        assertThat(result.getCode()).isEqualTo(-100);
        verifyNoInteractions(quizProgressCache);
    }

    // ── 测试数据 ────────────────────────────────────────────────────────────

    private void givenUser(int level, String apiKey) {
        User user = new User();
        user.setOpenid(OPENID);
        user.setLevel(level);
        user.setApiKey(apiKey);
        when(userService.findByOpenid(OPENID)).thenReturn(user);
    }

    private static QuizQuestion question(int id, int answerIndex) {
        return new QuizQuestion(id, "题干" + id, List.of("A", "B", "C", "D"), answerIndex, "解析");
    }
}
