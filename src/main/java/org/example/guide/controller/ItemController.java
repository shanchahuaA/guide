package org.example.guide.controller;

import org.example.guide.pojo.Item;
import org.example.guide.service.IItemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ItemController {
    @Autowired
    private IItemService itemService;
    @RequestMapping("testItemList")
    public List<Item> testItemList() {
     List<Item> items =  itemService.getItemList();
        return items;
    }


}
