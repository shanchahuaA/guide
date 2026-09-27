package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽题响应体（#42 / 契约 §7.3）。
 *
 * <p><b>只有题号、题干、选项三样</b> —— 正确项与解析**绝不能**出现在这里。
 * 抓包就能看到响应，下发答案等于把"连对 10 题升级"变成人人可脚本刷的分。
 * 答案只在 {@link QuizAnswerDto} 里出现，而答题要走 {@code POST /api/teach/quiz/answer}，
 * 后端在那里比对下标。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizNextDto {

    private Integer id;

    private String stem;

    private List<String> options;

    /** 与 {@link AnswerDto#toMap()} 同一个理由：主源码路径上只有 Jackson 3，手工装配这一层 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("stem", stem);
        map.put("options", options == null ? List.of() : options);
        return map;
    }
}
