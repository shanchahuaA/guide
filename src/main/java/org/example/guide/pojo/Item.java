package org.example.guide.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.List;

/**
 * Peak游戏物品表 item
 */
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

    private String description;

    private String achievement;

    private String icon;

    private String nameZh;
}
