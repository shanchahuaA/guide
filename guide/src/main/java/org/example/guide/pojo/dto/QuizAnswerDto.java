package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判题响应体（#42 / 契约 §7.3）。
 *
 * <p>**正确项与解析只在这里出现**（票面 AC）：前端据此把对的那项标出来、把解析展开，
 * 同时用 {@code streak / level / levelName} 就地把身份条刷新，不必再打一次 profile。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizAnswerDto {

    /** 这一题是否答对 */
    private Boolean correct;

    /** 正确项下标，用于高亮 */
    private Integer answerIndex;

    /** 解析 */
    private String explanation;

    /** 本次作答后的连对数（升级或答错后为 0） */
    private Integer streak;

    /** 升级所需的连对数，恒 10 */
    private Integer streakTarget;

    /** 本次作答后的等级 */
    private Integer level;

    /** 等级中文名 */
    private String levelName;

    /** 与 {@link AnswerDto#toMap()} 同一个理由：主源码路径上只有 Jackson 3，手工装配这一层 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("correct", correct);
        map.put("answerIndex", answerIndex);
        map.put("explanation", explanation);
        map.put("streak", streak);
        map.put("streakTarget", streakTarget);
        map.put("level", level);
        map.put("levelName", levelName);
        return map;
    }
}
