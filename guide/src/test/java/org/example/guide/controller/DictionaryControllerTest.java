package org.example.guide.controller;

import org.example.guide.utils.BaseResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个字典端点的契约断言（契约 §4 / §5）。
 *
 * <p>这一层是**纯静态字典**、不碰数据库：控制器没有依赖，直接 new 出来调。
 * 不写成 {@code @SpringBootTest} 也不写成 MockMvc —— 前者会把整个应用连同数据源一起拖起来，
 * 这两个端点恰恰是"不查库"的那一类，起了数据库反而验证不了这句。
 *
 * <p>字段面钉死在这里的原因与列表 DTO 一样：接口的 key 是前端写死的（小程序
 * {@code utils/api.js} 读 {@code data.tags} / {@code data.biomes}），改一个 key 就白屏。
 */
class DictionaryControllerTest {

    private final DictionaryController controller = new DictionaryController();

    @Test
    void tagsEndpointCarriesAllSixDimensionsInTheEnvelope() {
        BaseResult result = controller.tags();

        assertTrue(result.getSuccess(), "success 应为 true，否则小程序 fetchAll 会 reject");
        assertEquals(200, result.getCode());

        List<?> tags = (List<?>) result.getData().get("tags");
        assertNotNull(tags, "data.tags 缺失");
        assertFalse(tags.isEmpty(), "字典不该是空的");

        // 六个维度必须都出现 —— 少一个维度前端那格筛选就没有可选项。
        // 比的是 Set：只关心"有哪几个"（顺序由控制器的断言之外的部分钉住，JSON 对象里的次序不是契约）
        Set<String> dimensions = tags.stream().map(t -> field(t, "code")).collect(Collectors.toSet());
        assertEquals(Set.of("type", "biome", "rarity", "source", "location", "flag"), dimensions);
    }

    @Test
    void tagElementShapeIsExactlyCodeValueNameZh() {
        for (Object tag : (List<?>) controller.tags().getData().get("tags")) {
            Map<?, ?> map = (Map<?, ?>) tag;
            // keySet 比的是 Set：元素的**字段集**是契约，JSON 对象内 key 的先后不是
            assertEquals(Set.of("code", "value", "nameZh"), map.keySet(),
                    "元素形状必须与条目里 tags 的元素完全一致（前端一套代码认两种）：" + map);
            assertFalse(String.valueOf(map.get("value")).isBlank(), "value 不该为空：" + map);
            assertFalse(String.valueOf(map.get("nameZh")).isBlank(), "字典里的取值都该有中文名：" + map);
        }
    }

    @Test
    void tagsCountMatchesTheSettledDictionary() {
        List<?> tags = (List<?>) controller.tags().getData().get("tags");
        // type 12 / biome 11 / rarity 7 / source 19 / location 9 / flag 2 = 60（契约 §4 的定稿清单）
        assertEquals(60, tags.size(), "字典条目数变了：要么是数据源新增取值，要么是有人动了 TagDictionary");
    }

    @Test
    void biomesEndpointReturnsElevenWithoutCount() {
        BaseResult result = controller.biomes();

        assertTrue(result.getSuccess());
        assertEquals(200, result.getCode());

        List<?> biomes = (List<?>) result.getData().get("biomes");
        assertNotNull(biomes, "data.biomes 缺失");
        assertEquals(11, biomes.size(), "生态是 TagDictionary.BIOME_ZH 的 11 个");

        for (Object biome : biomes) {
            Map<?, ?> map = (Map<?, ?>) biome;
            // 只有 value / nameZh 两个字段：带条目计数是旧口径，已按契约 Q17 收口掉
            assertEquals(Set.of("value", "nameZh"), map.keySet(),
                    "生态元素只有 value / nameZh，不带 count：" + map);
            assertFalse(String.valueOf(map.get("value")).isBlank());
            assertFalse(String.valueOf(map.get("nameZh")).isBlank(), "中文名不该为空：" + map);
        }
    }

    /** 元素是纯 Map，取字段走 Map.get —— 取不到就是 null，不会像属性写法那样静默 */
    private static String field(Object element, String name) {
        return String.valueOf(((Map<?, ?>) element).get(name));
    }
}
