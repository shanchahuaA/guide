package org.example.guide.controller;

import org.example.guide.dictionary.TagDictionary;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 图鉴字典的两个端点（契约 §4 / §5）。
 *
 * <p><b>两者都不查库</b>：字典是静态的,写死在 {@link TagDictionary} 里,不随采集变化。
 * 所以这个控制器不注入任何 service —— 有意为之:一旦有人往这儿塞了个 repository,
 * "字典不查库"这条口径就破了,而它恰恰是本票要钉住的东西之一。
 *
 * <p>两个端点首期**没有消费方**（二级筛选不做）,但必须存在:小程序
 * {@code utils/api.js} 的 {@code fetchAll()} 启动时并发拉 items + tags + biomes,
 * 它读的是响应体里的 {@code success} 字段,缺一个端点整次启动拉取就整体失败。
 */
@RestController
public class DictionaryController {

    /**
     * 标签字典全量：扁平数组 {@code [{code, value, nameZh}]},覆盖六个维度。
     *
     * <p>扁平而不是按维度分组,是为了让元素的形状与条目详情里 {@code tags} 的元素**完全一致** ——
     * 前端一套代码就能认两种来源的标签（契约 §4）。{@code code} 是维度词,所以扁平也不丢信息。
     */
    @GetMapping("/api/tags")
    public BaseResult tags() {
        List<Map<String, Object>> tags = new ArrayList<>();
        for (String dimension : TagDictionary.dimensions()) {
            // TreeMap：维度内按 value 升序。map 的迭代顺序本身是未指定的,不排的话
            // 同一份字典在不同 JVM 启动里会给出不同次序,前端面板的标签跟着跳
            for (Map.Entry<String, String> e : new TreeMap<>(TagDictionary.entries(dimension)).entrySet()) {
                tags.add(tagElement(dimension, e.getKey(), e.getValue()));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tags", tags);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, data);
    }

    /**
     * 生态字典：{@code [{value, nameZh}]} 共 11 个,**不带条目计数**（契约 Q17）。
     *
     * <p>它就是 {@code /api/tags} 里 {@code code = "biome"} 那一个维度,独立成端点只因前端
     * {@code fetchAll} 点名要它。刻意不给计数:同一个生态下有多少条目是**采集结果**,
     * 会随数据源变化,而这个端点的定位是静态字典。
     */
    @GetMapping("/api/biomes")
    public BaseResult biomes() {
        List<Map<String, Object>> biomes = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(TagDictionary.entries(TagDictionary.BIOME)).entrySet()) {
            Map<String, Object> biome = new LinkedHashMap<>();
            biome.put("value", e.getKey());
            biome.put("nameZh", e.getValue());
            biomes.add(biome);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("biomes", biomes);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, data);
    }

    /**
     * 一个字典元素的形状。**元素形状与条目详情里 {@code tags} 的元素完全一致**（契约 §4）：都是
     * {@code {code, value, nameZh}} 三个字段，前端一套代码认两种来源。{@code code} 是维度词，所以扁平也不丢信息。
     *
     * <p>这里**不经过 {@link ItemTag}**：为了方便就把一个 JSON 列的元素 POJO 当接口 DTO 用，
     * 等于把"条目里那个元素长什么样"和"接口下发什么"重新焊死在一起 —— 契约 §0.2 禁止的正是这种直传。
     * 前端要的是字段，不是类型，{@code {code, value, nameZh}} 照契约组装即可。
     */
    private static Map<String, Object> tagElement(String dimension, String value, String nameZh) {
        return Map.of("code", dimension, "value", value, "nameZh", nameZh);
    }
}
