package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 教学页身份条的数据（契约 §7.1）。
 *
 * <p>本单里 {@code streak} 恒 0、{@code hasApiKey} 恒 false —— 连对计数与 API Key 都还没落地，
 * 这是**真值**不是占位。{@code streakTarget} 恒 10（连对 10 题升级，见 CONTEXT.md「用户等级」）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileDto {
    private Integer level;
    private String levelName;
    private Integer streak;
    private Integer streakTarget;
    private Boolean hasApiKey;

    /** 与 {@link ItemDetailDto#toMap()} 同一个理由：主源码路径上只有 Jackson 3，手工装配这一层 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", level);
        map.put("levelName", levelName);
        map.put("streak", streak);
        map.put("streakTarget", streakTarget);
        map.put("hasApiKey", hasApiKey);
        return map;
    }
}
