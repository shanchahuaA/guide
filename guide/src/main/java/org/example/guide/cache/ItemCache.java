package org.example.guide.cache;

import org.example.guide.mapper.ItemMapper;
import org.example.guide.pojo.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 图鉴全量条目的缓存（契约 §8.3、CONTEXT.md 缓存节）。
 *
 * <p>口诀是「MySQL 是权威，Redis 是缓存」：缓存读不到必须回查 MySQL 并回填（**回源**），
 * 否则 Redis 一被清空，图鉴就整个不可用。
 *
 * <p><b>只有两个方法</b>，而且有意不再多：
 * <ul>
 *   <li>{@link #getAll()} —— 命中即返；未命中（key 不在 / Redis 挂了 / 内容解不出来）回源 MySQL 并回填；</li>
 *   <li>{@link #evict()} —— 删 key。</li>
 * </ul>
 *
 * <p><b>只占一个 key</b>（{@value #KEY}），存全量条目的 JSON 数组，**不设 TTL**。
 * 不按主类型或筛选组合拆 key：拆主类型会让跨界条目（{@code Scorpion} 同时是 Food 与 Enemy）
 * 一份数据两处存；拆组合则 key 爆炸而命中率极低。筛选、搜索、生熟拆分、裁 DTO 全在应用层内存里做，
 * 所以这里的出口就是**原始的 {@code List<Item>}**，不是任何 DTO —— 缓存不该知道接口长什么样。
 *
 * <p><b>为什么直接注入 {@link ItemMapper} 而不是 {@code IItemService}</b>：{@code ItemServiceImpl}
 * 反过来要用本组件取数，注入 service 会构成循环依赖（契约 §8.3 明写）。
 */
@Component
public class ItemCache {

    private static final Logger log = LoggerFactory.getLogger(ItemCache.class);

    /**
     * 图鉴全量条目唯一的 key。
     *
     * <p>{@code public} 有一处真实消费方：验收端点 {@code CacheInspectController}
     * （{@code GET /cache/item-all}）用它回报"图鉴全量到底占哪个 key"，
     * 冒烟脚本据此把契约 §8.3 的 key 名钉死。key 名只有一个字面量，不抄第二份。
     */
    public static final String KEY = "guide:item:all";

    /**
     * 反序列化的目标类型 {@code List<Item>}。
     *
     * <p>**必须显式构造，不能写成 {@code Item[].class} 或 {@code Object.class}**：
     * 后者读出来是一堆 {@code LinkedHashMap}，{@code item.getTag()} 直接 ClassCastException；
     * 而只有指到 {@link Item} 上，两个 JSON 列（{@code tag} / {@code effect}）才会被 Jackson
     * 还原成 {@code List<ItemTag>} / {@code List<Effect>} —— 列表按 {@code primaryType} 分组、
     * 详情拆生熟两栏都建立在它们之上，读回来变一层 Map 就会全线崩。
     *
     * <p>用 Spring 容器里的 {@code ObjectMapper} 而不是自己 {@code new} 一个：
     * 本版本 Spring Boot 给的是 Jackson 3（{@code tools.jackson}），容器里那个已经按
     * {@code spring.jackson.*} 配好，自己 new 会把配置差异变成"缓存里存的和直查库的不一样"。
     */
    private final JavaType listOfItemType;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ItemMapper itemMapper;

    public ItemCache(StringRedisTemplate redis, ObjectMapper objectMapper, ItemMapper itemMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.itemMapper = itemMapper;
        this.listOfItemType = objectMapper.getTypeFactory().constructCollectionType(List.class, Item.class);
    }

    /**
     * 全量条目。
     *
     * <p>与缓存相关的任何异常都**不上抛**：Redis 连不上时演示环境要求接口照常返（走回源那条路），
     * 把异常抛出去只会让"Redis 挂了"升级成"图鉴整个不可用"。
     */
    public List<Item> getAll() {
        try {
            String cached = redis.opsForValue().get(KEY);
            if (cached != null) {
                return objectMapper.readValue(cached, listOfItemType);
            }
            log.info("缓存未命中，回源 MySQL：key={}", KEY);
        } catch (Exception e) {
            log.warn("读缓存失败，回源 MySQL：key={}", KEY, e);
        }

        List<Item> items = loadFromDb();
        try {
            redis.opsForValue().set(KEY, objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            // 回填失败只影响下一次请求（还得再查一次库），这一次已经把数据给出去了，
            // 所以不因为"写不进缓存"把成功的回源变成失败
            log.warn("回填缓存失败：key={}", KEY, e);
        }
        return items;
    }

    /**
     * 删掉唯一的那个 key。采集跑完调它（{@code CrawlerServiceImpl.evictItemCache}），
     * 下一次请求自然回源到采集后的新数据 —— 不用重启应用。
     */
    public void evict() {
        try {
            redis.delete(KEY);
            log.info("图鉴缓存已失效：key={}", KEY);
        } catch (Exception e) {
            log.warn("缓存失效失败：key={}", KEY, e);
        }
    }

    /**
     * 预热：应用启动时把全量图鉴灌进缓存。
     *
     * <p>**本身不吞异常** —— 与缓存相关的失败已经在 {@link #getAll()} 里消化掉了，
     * 这里再 catch 只会把"回源也失败了"这条信息也盖住。**兜住它的是一层 runner**
     * （{@code StartupWarmUp}，契约 §8.3 要求的那个 {@code ApplicationRunner}）：
     * 那边 try/catch 只 log.warn，因此演示环境里 Redis / MySQL 谁先起来都不会挡应用启动。
     */
    public void warmUp() {
        List<Item> items = getAll();
        log.info("图鉴缓存预热完成：key={}，{} 条", KEY, items.size());
    }

    /** 回源。直接走 mapper，查询条件与原先 {@code ItemServiceImpl.getItemList()} 完全一致（全表） */
    private List<Item> loadFromDb() {
        return itemMapper.selectList(null);
    }

    /**
     * 把条目序列化成缓存里那一串 JSON —— **只为测试开门**（{@code ItemCacheTest} 包内可见）。
     *
     * <p>存在的理由：测试要造"缓存里已经有东西"这个局面。若测试自己另 relay 一遍
     * {@code new ObjectMapper()} + {@code JavaType}，那份造法与 {@link #getAll()} 里读的那份
     * 一旦分叉，测的就是**另一件事**了。用组件自己的这一手，写进去的和读出来的保证同一份口径。
     */
    String serialize(List<Item> items) {
        return objectMapper.writeValueAsString(items);
    }

    /** {@link #serialize} 的逆操作，同一个理由：让测试用组件自己认那份类型，而不是另抄一份 */
    List<Item> deserialize(String json) {
        return objectMapper.readValue(json, listOfItemType);
    }

    /**
     * 缓存 key 当前的样子 —— **只为验收脚本开门**（{@code CacheInspectController}）。
     *
     * <p>缓存有没有生效，从接口响应上**完全看不出来**（那正是设计意图），所以"单 key / 无 TTL /
     * 全量"这三条契约口径只能从内部观察。Redis 不可达时返回 {@code reachable=false} 而**不上抛** ——
     * 与 {@link #getAll()} 同一套容错：Redis 连不上不是错误，是"走回源"的正常状态。
     *
     * <p><b>只用 {@code GET} + {@code TTL}，有意不用 {@code DEL}</b>：删除是破坏性动作，
     * 让它挂在一个 {@code GET} 端点后面，任何一次误访问都会把缓存打掉。删除走
     * {@code POST /cache/item-all/evict}，要删就必须显式改动词。
     *
     * <p>**别拿它当"接口确实在读缓存"的权威**：它走的是与本组件不同的读取路径。
     * 能证明这一点的只有冒烟脚本里那一步 ——真删掉 key、看接口是否照常返并自动回填。
     */
    public Inspection inspect() {
        try {
            String raw = redis.opsForValue().get(KEY);
            if (raw == null) {
                return new Inspection(true, KEY, -2L, 0, 0L);
            }
            List<Item> items = objectMapper.readValue(raw, listOfItemType);
            Long ttl = redis.getExpire(KEY);
            return new Inspection(true, KEY, ttl, items.size(), raw.length());
        } catch (Exception e) {
            log.warn("查看缓存失败：key={}", KEY, e);
            return new Inspection(false, KEY, null, 0, 0L);
        }
    }

    /**
     * 缓存 key 的快照。
     *
     * @param reachable Redis 是否可达（false 时其余字段没有意义）
     * @param key       key 名，恒为 {@link #KEY}
     * @param ttl       剩余过期秒数：{@code -1} 永不删、{@code -2} key 不存在
     * @param count     缓存里条目的条数
     * @param bytes     JSON 串的字符数
     */
    public record Inspection(boolean reachable, String key, Long ttl, int count, long bytes) {
    }
}
