package org.example.guide.service;

import com.baomidou.mybatisplus.spring.service.IService;
import org.example.guide.pojo.Item;

import java.util.List;

public interface IItemService extends IService<Item> {
    /** 查询全部物品（前端列表展示用） */
    List<Item> getItemList();

    /** 按 id 查询单个物品详情 */
    Item getItemById(Long id);

    /** 按关键词检索名称(中/英)，前端搜索用 */
    List<Item> searchItems(String keyword);

    /** 爬虫批量灌数据，已存在(按 nameEn)则更新，否则插入 */
    boolean batchImportItems(List<Item> items);
}
