package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答响应体（契约 §7.4）。
 *
 * <p>{@code links} 本单（#41）**恒为空数组**，但键必须在：路线类问题的链接归 #44，
 * 小程序那边读的是固定字段面，少一个键以后要改前端。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerDto {

    private String answer;

    private List<Map<String, Object>> links;

    /** 只有答案这一种形态时用它 —— links 补成空数组，键不缺席 */
    public AnswerDto(String answer) {
        this(answer, List.of());
    }

    /** 与 {@link ItemDetailDto#toMap()} 同一个理由：主源码路径上只有 Jackson 3，手工装配这一层 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("answer", answer);
        map.put("links", links == null ? List.of() : links);
        return map;
    }
}
