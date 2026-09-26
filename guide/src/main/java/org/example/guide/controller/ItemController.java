package org.example.guide.controller;

import org.example.guide.pojo.Item;
import org.example.guide.service.IItemService;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ItemController {
    @Autowired
    private IItemService itemService;

    /**
     * 图鉴列表：一次返全量条目（含 2 条游戏内已移除的，不过滤）。
     *
     * **无参数、不分页** —— 一级导航与关键词搜索都在小程序本地做，
     * 接口只负责把全量给出去（契约 §0.5）。列表字段见 ItemListDto，长文本与 tags 都不下发。
     */
    @GetMapping("/api/items")
    public BaseResult listItems() {
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, Map.of("items", itemService.getListItemDtos()));
    }

    /** 早期自测端点，保留不删（契约 §8.4） */
    @RequestMapping("testItemList")
    public List<Item> testItemList() {
     List<Item> items =  itemService.getItemList();
        return items;
    }


}
