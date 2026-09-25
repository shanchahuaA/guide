package org.example.guide.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "user", autoResultMap = true)
public class User {
    /** 微信 openid，即主键 */
    @TableId(type = IdType.INPUT)
    private String openid;
    /** 等级：0菜鸟 1入门 2高手 */
    private Integer level;
    /** 用户提供的 AI key */
    private String apiKey;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 昵称 */
    private String nickname;
    /** 头像URL */
    private String avatar;
}
