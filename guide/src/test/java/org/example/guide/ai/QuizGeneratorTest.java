package org.example.guide.ai;

import org.example.guide.pojo.Item;
import org.example.guide.pojo.QuizQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 题库的 JSON 解析与重试（#42）。**不真调 DeepSeek** —— 解析用假的响应文本，
 * 重试用 mock 的客户端钉住"只重试解析失败这一种"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizGeneratorTest {

    @Mock
    private DeepSeekClient deepSeekClient;

    private QuizGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new QuizGenerator(deepSeekClient, JsonMapper.builder().build());
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Test
    void 解析出题目并按顺序重新编号() {
        List<QuizQuestion> questions = generator.parse("""
                {"questions":[
                  {"stem":"Q1","options":["A","B","C","D"],"answerIndex":2,"explanation":"E1"},
                  {"stem":"Q2","options":["A","B","C","D"],"answerIndex":0,"explanation":"E2"}
                ]}
                """);

        assertThat(questions).hasSize(2);
        assertThat(questions).extracting(QuizQuestion::getId).containsExactly(0, 1);
        assertThat(questions.get(0).getStem()).isEqualTo("Q1");
        assertThat(questions.get(0).getOptions()).containsExactly("A", "B", "C", "D");
        assertThat(questions.get(0).getAnswerIndex()).isEqualTo(2);
        assertThat(questions.get(0).getExplanation()).isEqualTo("E1");
    }

    @Test
    void 结构不对的题被跳过而其余照常且重编号连续() {
        // 选项只有三个、正确项越界的都丢弃；剩下那条重新编号为 0，不留空洞
        List<QuizQuestion> questions = generator.parse("""
                {"questions":[
                  {"stem":"bad-options","options":["A","B","C"],"answerIndex":0,"explanation":"x"},
                  {"stem":"bad-answer","options":["A","B","C","D"],"answerIndex":9,"explanation":"x"},
                  {"stem":"good","options":["A","B","C","D"],"answerIndex":1,"explanation":"ok"}
                ]}
                """);

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).getStem()).isEqualTo("good");
        assertThat(questions.get(0).getId()).isZero();
    }

    @Test
    void 兼容模型偶尔直接给数组() {
        List<QuizQuestion> questions = generator.parse(
                "[{\"stem\":\"Q\",\"options\":[\"A\",\"B\",\"C\",\"D\"],\"answerIndex\":3,\"explanation\":\"E\"}]");

        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).getAnswerIndex()).isEqualTo(3);
    }

    @Test
    void 不是JSON时抛出可重试的AiException() {
        assertThatThrownBy(() -> generator.parse("这不是 JSON"))
                .isInstanceOf(AiException.class)
                .extracting(e -> ((AiException) e).getCode())
                .isEqualTo(-100);
    }

    @Test
    void 没有可解析题目时抛AiException() {
        assertThatThrownBy(() -> generator.parse("{\"questions\":[]}"))
                .isInstanceOf(AiException.class);
    }

    // ── 重试 ────────────────────────────────────────────────────────────────

    @Test
    void 第一次解析失败会重试并返回第二次的结果() {
        when(deepSeekClient.chatJson(anyString(), anyString(), anyString()))
                .thenReturn("不是 JSON")
                .thenReturn(bankJson(QuizGenerator.MIN_QUESTIONS));

        List<QuizQuestion> questions = generator.generate("sk-good", List.of(item()), 0);

        assertThat(questions).hasSize(QuizGenerator.MIN_QUESTIONS);
        verify(deepSeekClient, times(2)).chatJson(anyString(), anyString(), anyString());
    }

    @Test
    void 题数不够也算失败并重试() {
        // 短于 MIN_QUESTIONS 的题库会让"本周期内已答对的题不再出"在周期走完前无题可出，
        // 所以题数不够与解析失败同待遇：重试，绝不写进缓存
        when(deepSeekClient.chatJson(anyString(), anyString(), anyString()))
                .thenReturn(bankJson(QuizGenerator.MIN_QUESTIONS - 1))
                .thenReturn(bankJson(QuizGenerator.MIN_QUESTIONS));

        List<QuizQuestion> questions = generator.generate("sk-good", List.of(item()), 0);

        assertThat(questions).hasSize(QuizGenerator.MIN_QUESTIONS);
        verify(deepSeekClient, times(2)).chatJson(anyString(), anyString(), anyString());
    }

    @Test
    void 连续解析失败达到上限后抛异常() {
        when(deepSeekClient.chatJson(anyString(), anyString(), anyString())).thenReturn("始终不是 JSON");

        assertThatThrownBy(() -> generator.generate("sk-good", List.of(item()), 0))
                .isInstanceOf(AiException.class);

        verify(deepSeekClient, times(QuizGenerator.MAX_ATTEMPTS)).chatJson(anyString(), anyString(), anyString());
    }

    @Test
    void 出题走的是JSON模式不是普通对话() {
        when(deepSeekClient.chatJson(anyString(), anyString(), anyString()))
                .thenReturn(bankJson(QuizGenerator.MIN_QUESTIONS));

        generator.generate("sk-good", List.of(item()), 0);

        verify(deepSeekClient).chatJson(anyString(), anyString(), anyString());
        // 自然语言的 chat 拿不到结构化的题库，用错方法等于白调
        verify(deepSeekClient, never()).chat(anyString(), anyString(), anyString());
    }

    private static Item item() {
        Item item = new Item();
        item.setId(1L);
        item.setNameEn("Hot Dog");
        item.setNameZh("热狗肠");
        return item;
    }

    /** n 道合法题目的响应串，题 i 的正确项是 i % 4 —— 出题路径的测试夹具 */
    private static String bankJson(int n) {
        StringBuilder sb = new StringBuilder("{\"questions\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"stem\":\"Q").append(i)
              .append("\",\"options\":[\"A\",\"B\",\"C\",\"D\"],\"answerIndex\":").append(i % 4)
              .append(",\"explanation\":\"E").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }
}
