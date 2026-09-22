package org.example.guide.pojo;

import lombok.Data;

/**
 * 物品标签,对应 item.tag 这个 JSON 列里的一个元素
 */
@Data
public class ItemTag {
    /** 标签代码,如 NATURAL_FOOD / BERRY */
    private String code;

    /** 中文名,由我们自定义,如 天然食物 / 浆果 */
    private String nameZh;
}
