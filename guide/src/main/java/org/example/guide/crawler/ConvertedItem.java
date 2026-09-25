package org.example.guide.crawler;

import org.example.guide.pojo.Item;

import java.util.List;

/**
 * 一条数据源行转换出来的东西:图鉴条目 + 这条里出现的字典未知取值。
 *
 * 未知取值跟着结果一起交回给采集服务,转换器本身因此可以保持无状态 ——
 * 不用把警告攒在实例字段里,也就不会在并发采集时串味。
 */
public record ConvertedItem(Item item, List<String> unknownDictionaryValues) {
}
