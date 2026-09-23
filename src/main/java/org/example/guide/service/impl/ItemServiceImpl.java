package org.example.guide.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.session.ResultHandler;
import org.example.guide.mapper.ItemMapper;
import org.example.guide.pojo.Item;
import org.example.guide.service.IItemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class ItemServiceImpl extends ServiceImpl<ItemMapper,Item> implements IItemService{
    @Autowired
    private ItemMapper itemMapper;

    @Override
    public List<Item> getItemList(){
        return baseMapper.selectList(null);
    }
 }
