package org.example.guide.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Peak游戏物品表 item
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@TableName(value = "item", autoResultMap = true)
public class Item {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ItemTag> tag;

    private Float weight;

    private String nameEn;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Effect> effect;

    /** 英文描述。页面级长文本，**不下发给小程序**（契约 §2） */
    private String description;

    /** 中文描述。详情的**唯一**描述字段，英文那份不下发（契约 §2.2） */
    @TableField("description_zh")
    private String descriptionZh;

    private String achievement;

    private String icon;

    private String nameZh;
}
