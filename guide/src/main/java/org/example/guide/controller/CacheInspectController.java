package org.example.guide.controller;

import org.example.guide.cache.ItemCache;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图鉴缓存的验收口（{@code scripts/smoke-test.ps1} 的"缓存（单 key 语义）"那一组用）。
 *
 * <p><b>为什么需要一个新端点</b>：缓存有没有生效，从接口响应上**完全看不出来** ——
 * 这正是设计意图（契约 §0.1 的响应形状不因为加缓存而变）。所以在服务端加标记会污染契约，
 * 而"删一次 key 再请求"这个动作脚本自己做不到（浏览器之外没有 Redis 客户端）。
 * 这个端点是唯一的窄口：只认"缓存这一个 key"，不发条目、不暴露 Redis 通用命令。
 *
 * <p>它与冒烟脚本里读 {@code data.items} / {@code data.tags} 那些断言不冲突：
 * 契约 §0.1 只规定 {@code /api/**} 下的**图鉴相关**端点长什么样（{@code /api/tags}、
 * {@code /api/biomes} 那些），不覆盖一个全新的自测口 —— 与 {@code /testItemList} 同一性质。
 */
@RestController
public class CacheInspectController {

    private final ItemCache itemCache;

    public CacheInspectController(ItemCache itemCache) {
        this.itemCache = itemCache;
    }

    /**
     * 看缓存 key 当前的样子：key 名 / TTL / 条数 / JSON 串长度。
     *
     * <p>Redis 连不上时 {@code reachable=false}，**仍然是 200**：对脚本来说"Redis 没起"
     * 是"跳过这组断言"的信号，不是失败 —— 契约 §8.3 要求接口在 Redis 挂掉时照常可用，
     * 这条端点自己当然也得守同一条。所以判据是 {@code data.reachable}，不是 HTTP 状态码。
     */
    @GetMapping("/cache/item-all")
    public BaseResult inspect() {
        ItemCache.Inspection inspection = itemCache.inspect();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reachable", inspection.reachable());
        data.put("key", inspection.key());
        data.put("ttl", inspection.ttl());
        data.put("count", inspection.count());
        data.put("bytes", inspection.bytes());
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, data);
    }

    // ------------------------------------------------------------------
    // 以下端点已停用（映射注释掉），保留代码仅作记录。
    //
    // 停用原因：这是个**破坏性**动作（删缓存），却和只读的 inspect 一样落在
    // 全放行的路径下 —— ShiroConfig 的过滤链当前是 `/** = anon`，
    // 而 CLAUDE.md 记录的收口方案只覆盖 `/admin/**`，不覆盖 `/cache/**`。
    // 也就是说它不会「以后自然收口」，而是一直敞着：任何能打到这个地址的人
    // 都能清掉图鉴缓存。影响可控（下次请求回源 MySQL，不丢数据），但它是
    // 可写口，不是只读探针 —— 与 `/testItemList` 不是一回事。
    //
    // 验收改为走 `redis-cli` 直接删 key，不需要一个常驻接口来代劳。
    // 将来若 #5（Shiro 登录与拦截）完成、`/cache/**` 有了鉴权，可以放开。
    // ------------------------------------------------------------------
    //
    // /** 删掉图鉴的全量缓存 —— 模拟"Redis 被清空 / key 过期"，
    //  * 随后请求 {@code /api/items} 应当自动回源并回填。 */
    // @PostMapping("/cache/item-all/evict")
    // public BaseResult evict() {
    //     itemCache.evict();
    //     Map<String, Object> data = new LinkedHashMap<>();
    //     data.put("evicted", true);
    //     data.put("key", ItemCache.KEY);
    //     return BaseResult.setResult(ResultCodeEnum.SUCCESS, data);
    // }
}
