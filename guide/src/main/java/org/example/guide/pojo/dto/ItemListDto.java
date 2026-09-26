package org.example.guide.pojo.dto;

import lombok.Data;
import org.example.guide.pojo.Item;
import org.example.guide.utils.ItemFields;

/**
 * 图鉴**列表**条目的接口响应结构（契约 §1）。
 *
 * <p>{@code slug} 与 {@code primaryType} 是计算值,表里没有这两列（口径见
 * {@link ItemFields}）；一级导航就靠 {@code primaryType} 一个字段,前端在本地按它切格。
 *
 * <p>字段是**有意收窄**的,不给的东西都在这份名单的"为什么不给"里：
 * <ul>
 *   <li>{@code description} / {@code descriptionZh} —— 中文描述最长 391 字,134 条全带会让 payload 涨三倍;</li>
 *   <li>{@code tags} —— 首期不做二级筛选,带了没有消费方;</li>
 *   <li>{@code id} —— 详情靠 slug 跳转,id 是库内自增、换机器重灌数据就会变。</li>
 * </ul>
 */
@Data
public class ItemListDto {

    /** 详情页路径参数,由 nameEn 派生（{@link ItemFields#slugOf}） */
    private String slug;

    /** 列表中英名都要显示 */
    private String nameZh;

    private String nameEn;

    /** 相对路径,如 /icons/Hot_Dog.png */
    private String icon;

    /** 一位小数、可为负 */
    private Float weight;

    /** 一级导航的落点,取值见 {@link ItemFields#PRIMARY_TYPE_ORDER} */
    private String primaryType;

    public static ItemListDto from(Item item) {
        ItemListDto dto = new ItemListDto();
        dto.setSlug(ItemFields.slugOf(item));
        dto.setNameZh(item.getNameZh());
        dto.setNameEn(item.getNameEn());
        dto.setIcon(item.getIcon());
        dto.setWeight(item.getWeight());
        dto.setPrimaryType(ItemFields.primaryTypeOf(item));
        return dto;
    }
}
