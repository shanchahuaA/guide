package org.example.guide.service;

import com.baomidou.mybatisplus.spring.service.IService;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.dto.ItemDetailDto;
import org.example.guide.pojo.dto.ItemListDto;

import java.util.List;

public interface IItemService extends IService<Item> {
    /** 查询全部物品（前端列表展示用） */
    List<Item> getItemList();

    /**
     * 全量条目的列表视图（GET /api/items 用）。
     *
     * 按 primaryType 分组、组内按 id 升序 —— 不排序的话每次查询的返回次序都可能不同。
     * 已移除的条目照常返回，这是明确的产品决定。
     */
    List<ItemListDto> getListItemDtos();

    /**
     * 按 slug 取单个条目的详情视图（GET /api/items/{slug} 用）。
     *
     * slug 是计算值、表里没有那一列，所以只能一次拉全量再按派生出来的 slug 匹配
     * （134 条的量级，比给 slug 建列或加缓存都划算；契约 §0.4 又明说这三个计算值不落库）。
     *
     * @return 找不到时返回 {@code null}，由控制器转成 code=-100 的失败响应 ——
     *         **不是** 200 带 data:null，否则前端分不清"没有这条"和"后端挂了"（契约 §2.3）
     */
    ItemDetailDto getDetailDto(String slug);

    /** 按 id 查询单个物品详情 */
    Item getItemById(Long id);

    /** 按关键词检索名称(中/英)，前端搜索用 */
    List<Item> searchItems(String keyword);
}
