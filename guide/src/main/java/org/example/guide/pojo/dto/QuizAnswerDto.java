package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判题响应体（#42 / 契约 §7.3）：**正确项与解析只在这里出现**。
 *
 * <p>前端据此把对的那项标出来、把解析展开；连对与等级不在这个响应里回带 ——
 * 身份条的数据源始终是 {@code /api/teach/profile} 那一个，答题后前端就地去刷它，
 * 两处都下发同一组值只会让"谁是权威"变得含糊。
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

    /** 与 {@link AnswerDto#toMap()} 同一个理由：主源码路径上只有 Jackson 3，手工装配这一层 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("correct", correct);
        map.put("answerIndex", answerIndex);
        map.put("explanation", explanation);
        return map;
    }
}
