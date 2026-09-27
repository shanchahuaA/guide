package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判题响应体（#42 / 契约 §7.3）：**正确项、解析与作答后的连对/等级都在这里**。
 *
 * <p>契约 §7.3 要求一并回带 {@code streak} / {@code level} / {@code upgraded} ——
 * 前端据此就地把身份条刷新，不必再打一次 profile。{@code level} 是**本次请求之后**的当前等级。
 *
 * <p>正确项与解析只在这个响应里出现（{@link QuizNextDto} 不带），答题要走
 * {@code POST /api/teach/quiz/answer}，后端在那里比对下标。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizAnswerDto {

    /** 这一题是否答对 */
    private Boolean correct;

    /** 正确项下标，用于高亮 */
    private Integer correctIndex;

    /** 解析 */
    private String explanation;

    /** 作答后的连对数（答错或升级后为 0） */
    private Integer streak;

    /** 作答后的当前等级（本次请求之后） */
    private Integer level;

    /** 本次作答是否触发升级（已是高手则恒 false） */
    private Boolean upgraded;

    /** 与 {@link AnswerDto#toMap()} 同一个理由：主源码路径上只有 Jackson 3，手工装配这一层 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("correct", correct);
        map.put("correctIndex", correctIndex);
        map.put("explanation", explanation);
        map.put("streak", streak);
        map.put("level", level);
        map.put("upgraded", upgraded);
        return map;
    }
}
