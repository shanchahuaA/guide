package org.example.guide.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.session.ResultHandler;
import org.example.guide.cache.ItemCache;
import org.example.guide.mapper.ItemMapper;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.dto.ItemDetailDto;
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

    /**
     * 图鉴全量条目的取数口。
     *
     * <p>列表与详情**都**改走它（契约 §8.3）：缓存只负责"把全量条目取出来"，
     * 分组、派生 slug、拆生熟、裁 DTO 仍然在这一层做。
     *
     * <p>方向是单向的：本类注入 {@link ItemCache}，{@code ItemCache} 只注入 {@code ItemMapper}。
     * 反过来让缓存组件注入 service 会构成循环依赖，所以缓存那边**不碰**这个类。
     */
    @Autowired
    private ItemCache itemCache;

    /**
     * 全量条目，走缓存（命中即返，未命中回源 MySQL 并回填）。
     *
     * <p>{@link #getItemList()} 有意**不改**：它是早期自测端点 {@code /testItemList} 的取数口，
     * 留着一条不经缓存的直查库路径，正好当"缓存里的数据与库一致"的对照物。
     */
    private List<Item> allItems() {
        return itemCache.getAll();
    }

    @Override
    public List<Item> getItemList(){
        return baseMapper.selectList(null);
    }

    @Override
    public List<ItemListDto> getListItemDtos(){
        return allItems().stream()
                // 组序：ItemFields.PRIMARY_TYPE_ORDER 的下标；组内按 id 升序。不定顺序的话每次查询的返回次序都可能不同
                .sorted(Comparator.comparingInt((Item item) -> ItemFields.PRIMARY_TYPE_ORDER.indexOf(ItemFields.primaryTypeOf(item)))
                                  .thenComparing(Item::getId))
                .map(ItemListDto::from)
                .toList();
    }

    @Override
    public ItemDetailDto getDetailDto(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        // slug 是计算值、不是列，SQL 里没有可匹配的东西 —— 只能拉全量再按派生值找。
        // 图鉴是读多写少的小表（134 条），换个"给 slug 建列"的方案反而要重灌数据。
        // 这份全量走缓存：原来注释里写的"比加缓存都划算"已经过期 —— 缓存本轮落下来了（契约 §8.3）
        return allItems().stream()
                .filter(item -> slug.equals(ItemFields.slugOf(item)))
                .findFirst()
                .map(ItemDetailDto::from)
                .orElse(null);
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
 }
