package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录响应体（契约 §6）：openid 与占位 token 值相同，外加当前等级。
 *
 * <p>固定 DTO —— 将来 token 换成真 JWT 时只改 {@code token} 这一列的来源，前端零改动。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginDto {
    private String openid;
    private String token;
    private Integer level;

    /** 与 {@link ItemDetailDto#toMap()} 同一个理由：主源码路径上只有 Jackson 3，手工装配这一层 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("openid", openid);
        map.put("token", token);
        map.put("level", level);
        return map;
    }
}
