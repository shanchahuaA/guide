package org.example.guide.pojo.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 列表 DTO 的字段面是**契约**：多一个字段老版本小程序不会因此白屏,
 * 少一个字段会;而多下发的长文本是实打实的流量。所以这里把字段集钉死。
 */
class ItemListDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesExactlyTheSixContractFields() {
        Map<String, Object> json = objectMapper.convertValue(ItemListDto.from(hotDog()), new TypeReference<>() {
        });
        assertEquals(Set.of("slug", "nameZh", "nameEn", "icon", "weight", "primaryType"), json.keySet());
    }

    @Test
    void carriesBothComputedFields() {
        ItemListDto dto = ItemListDto.from(hotDog());
        assertEquals("hot_dog", dto.getSlug());
        assertEquals("FOOD", dto.getPrimaryType());
    }

    private static Item hotDog() {
        Item item = new Item();
        item.setId(1L);
        item.setNameEn("Hot Dog");
        item.setNameZh("热狗肠");
        item.setIcon("/icons/Hot_Dog.png");
        item.setWeight(5.0F);
        item.setDescription("Hot Dog is a natural food...");
        item.setAchievement("大胃王奖章");
        List<ItemTag> tags = new ArrayList<>();
        ItemTag type = new ItemTag();
        type.setCode("type");
        type.setValue("Food");
        tags.add(type);
        item.setTag(tags);
        return item;
    }
}
