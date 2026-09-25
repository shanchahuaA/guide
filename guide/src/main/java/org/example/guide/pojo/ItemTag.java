package org.example.guide.pojo;

import lombok.Data;

/**
 * 物品标签,对应 item.tag 这个 JSON 列里的一个元素。
 *
 * 三个字段的分工:
 * - code 是**维度词**,取 TagDictionary 里的常量(type / biome / rarity / source / location / flag);
 * - value 保留**数据源原值**,零转换,随时能对回 wiki;
 * - nameZh 是展示用中文,由采集侧写死的字典给出;字典里查不到的取值会留空,
 *   并在采集报告里给出警告,而不是丢掉这条标签。
 */
@Data
public class ItemTag {
    /** 维度词,如 type / biome / rarity / source / location / flag */
    private String code;

    /** 数据源原值,如 Food / Gloom / Rare / removed */
    private String value;

    /** 中文名,如 食物 / 雾沼 / 稀有 / 已移除;字典未收录时为空 */
    private String nameZh;
}
