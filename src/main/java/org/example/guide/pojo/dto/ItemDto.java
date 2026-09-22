package org.example.guide.pojo.dto;

import lombok.Data;

import java.util.List;

import org.example.guide.pojo.Effect;
import org.example.guide.pojo.ItemTag;

/**
 * 图鉴条目的接口响应结构。
 *
 * 不直接把 Item 实体返回给小程序：数据库列可以随便加，但接口响应结构必须是稳定契约,
 * 否则改一个字段名,老版本小程序就渲染不出来。
 */
@Data
public class ItemDto {
    private Long id;
    private String nameEn;
    private String nameZh;
    private String icon;
    private Float weight;
    private List<ItemTag> tag;
    private List<Effect> effect;
    private String description;
    private String achievement;
}
