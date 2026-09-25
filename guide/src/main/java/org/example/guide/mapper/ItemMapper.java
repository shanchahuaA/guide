package org.example.guide.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.guide.pojo.Item;

@Mapper
public interface ItemMapper extends BaseMapper<Item> {

}
