package org.example.guide.controller;

import org.example.guide.pojo.Item;
import org.example.guide.pojo.dto.ItemDetailDto;
import org.example.guide.service.IItemService;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 图鉴详情：**路径参数是 slug 不是 id**（契约 §2），返回的 {@code data} **直接就是条目对象**。
     *
     * <p>字段口径见 {@link ItemDetailDto} —— 生熟拆成 raw / cooked 两栏、只给中文描述。
     *
     * <p>找不到时返 {@code code=-100} 的失败响应，**不是** 200 带 {@code data:null}：
     * 前端要能区分"没有这条"和"后端挂了"，否则两种情况的用户观感都是白屏（契约 §2.3）。
     */
    @GetMapping("/api/items/{slug}")
    public BaseResult getItemDetail(@PathVariable("slug") String slug) {
        ItemDetailDto detail = itemService.getDetailDto(slug);
        if (detail == null) {
            BaseResult notFound = BaseResult.setResult(ResultCodeEnum.FAILURE, null);
            notFound.setMessage("条目不存在：" + slug);
            return notFound;
        }
        // BaseResult.data 声明的就是 Map<String, Object>，**不为详情单独放宽它**（契约 §8.2）。
        // 顶层转成 Map 后，JSON 里 data 就是条目对象本身。
        //
        // 手工装配这一层、而不是契约建议的 objectMapper.convertValue：Spring Boot 4.1.1 的
        // spring-boot-starter-jackson 给的是 Jackson 3（tools.jackson），而 MyBatis-Plus 把
        // Jackson 2 的 databind 声明成 <optional>true</optional>、上游不继承 —— 所以主源码的
        // 编译路径上根本没有 Jackson 2 的 ObjectMapper。补一份 Jackson 2 依赖是另一个决定
        // （会与 Jackson 3 并存），不在本票范围内；字段面由 ItemDetailDtoTest 钉住。
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, detail.toMap());
    }

    /** 早期自测端点，保留不删（契约 §8.4） */
    @RequestMapping("testItemList")
    public List<Item> testItemList() {
     List<Item> items =  itemService.getItemList();
        return items;
    }


}
