package org.example.guide.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.session.ResultHandler;
import org.example.guide.mapper.ItemMapper;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.dto.ItemListDto;
import org.example.guide.service.IItemService;
import org.example.guide.utils.ItemFields;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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

    @Override
    public List<ItemListDto> getListItemDtos(){
        return baseMapper.selectList(null).stream()
                // 组序：ItemFields.PRIMARY_TYPE_ORDER 的下标；组内按 id 升序。不定顺序的话每次查询的返回次序都可能不同
                .sorted(Comparator.comparingInt((Item item) -> ItemFields.PRIMARY_TYPE_ORDER.indexOf(ItemFields.primaryTypeOf(item)))
                                  .thenComparing(Item::getId))
                .map(ItemListDto::from)
                .toList();
    }

    @Override
    public Item getItemById(Long id){
        return baseMapper.selectById(id);
    }

    @Override
    public List<Item> searchItems(String keyword){
        if (keyword == null || keyword.trim().isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<Item> wrapper = new LambdaQueryWrapper<>();
        // 必须嵌套成 (name_zh LIKE ? OR name_en LIKE ?)：MyBatis-Plus 不给顶层 .or() 链自动加括号，
        // 之后追加的条件只会绑到右操作数上（SQL 优先级下等价于 A OR (B AND C)），name_zh 命中的行就绕过了那个条件
        wrapper.and(w -> w.like(Item::getNameZh, keyword)
                          .or().like(Item::getNameEn, keyword));
        return baseMapper.selectList(wrapper);
    }

    @Override
    public boolean batchImportItems(List<Item> items){
        if (items == null || items.isEmpty()) {
            return false;
        }
        // 按 nameEn 去重：已存在则更新，不存在则插入
        for (Item item : items) {
            LambdaQueryWrapper<Item> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Item::getNameEn, item.getNameEn());
            Item exist = baseMapper.selectOne(wrapper);
            if (exist != null) {
                item.setId(exist.getId());
                baseMapper.updateById(item);
            } else {
                baseMapper.insert(item);
            }
        }
        return true;
    }
 }
