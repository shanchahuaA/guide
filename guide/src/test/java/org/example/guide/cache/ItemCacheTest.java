package org.example.guide.cache;

import org.example.guide.mapper.ItemMapper;
import org.example.guide.pojo.Effect;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.ItemTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ItemCache} 的行为测试。**不加载 Spring 上下文、不连真 Redis / MySQL** ——
 * 这里要钉的是**分支**（命中返什么、未命中做什么、Redis 挂了走哪条路），
 * 真 Redis 上的整链路由 {@code scripts/smoke-test.ps1} 的缓存那一组覆盖。
 *
 * <p>用 Mockito 而不是 {@code @MockitoBean}：本仓库的 {@code ObjectMapper} 是 Jackson 3，
 * 真造一个 {@code JsonMapper} 就能同时覆盖到"JSON 列往返"这一层真人真事，
 * 比把 mapper 也 mock 掉、只断言"有没有被调用"强得多。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItemCacheTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private ItemMapper itemMapper;

    private JsonMapper objectMapper;
    private ItemCache cache;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new ItemCache(redis, objectMapper, itemMapper);
    }

    // ── 回源 ────────────────────────────────────────────────────────────────

    @Test
    void 缓存未命中时回源数据库并把结果回填() {
        when(valueOps.get(ItemCache.KEY)).thenReturn(null);
        when(itemMapper.selectList(null)).thenReturn(List.of(hotDog()));

        List<Item> items = cache.getAll();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getNameEn()).isEqualTo("Hot Dog");
        verify(itemMapper).selectList(null);
        // 回填是"下一次请求不再查库"的唯一保证，漏了它缓存就永远只是穿透
        verify(valueOps).set(eq(ItemCache.KEY), anyString());
    }

    @Test
    void 缓存命中时不查数据库() {
        when(valueOps.get(ItemCache.KEY)).thenReturn(cache.serialize(List.of(hotDog(), firstAidKit())));

        List<Item> items = cache.getAll();

        assertThat(items).hasSize(2);
        verify(itemMapper, never()).selectList(any());
    }

    // ── JSON 列往返：本票最容易出错的地方 ───────────────────────────────────

    @Test
    void 命中时两个JSON列还原成实体元素而不是Map() {
        when(valueOps.get(ItemCache.KEY)).thenReturn(cache.serialize(List.of(hotDog())));

        Item item = cache.getAll().get(0);

        // 断言元素的**运行时类型**，不是"非空"：读回来若退化成 LinkedHashMap，
        // 详情按 flag=cookable 判可烹饪、按 _COOKED 拆生熟会当场 ClassCastException 或静默判错
        assertThat(item.getTag()).hasSize(2);
        assertThat(item.getTag().get(0)).isInstanceOf(ItemTag.class);
        assertThat(item.getTag()).extracting(ItemTag::getCode).containsExactly("type", "flag");
        assertThat(item.getTag()).extracting(ItemTag::getValue).containsExactly("Food", "cookable");

        assertThat(item.getEffect()).hasSize(2);
        assertThat(item.getEffect().get(0)).isInstanceOf(Effect.class);
        assertThat(item.getEffect()).extracting(Effect::getCode).containsExactly("HUNGER", "HUNGER_COOKED");
    }

    @Test
    void 一位小数的重量与可空字段往返后不变() {
        Item item = hotDog();
        item.setWeight(-2.5f);
        when(valueOps.get(ItemCache.KEY)).thenReturn(cache.serialize(List.of(item)));

        Item back = cache.getAll().get(0);

        // weight 是 Float，而 JSON 里是 5.0 / -2.5 —— 走 Map 中转一次就会变成 Double
        assertThat(back.getWeight()).isEqualTo(-2.5f);
        assertThat(back.getDescriptionZh()).isEqualTo("热狗肠是 PEAK 中的天然食物");
        // achievement 库里只有 28 条有，null 必须原样回来（不能变成字面量 "null"）
        assertThat(back.getAchievement()).isNull();
        // duration / startDelay 可空，也是同一个坑
        assertThat(back.getEffect().get(0).getDuration()).isNull();
    }

    // ── Redis 连不上：不抛异常，走回源那条路 ────────────────────────────────

    @Test
    void 读缓存抛连接异常时回源而不是上抛() {
        when(valueOps.get(ItemCache.KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(itemMapper.selectList(null)).thenReturn(List.of(hotDog()));

        List<Item> items = cache.getAll();

        assertThat(items).hasSize(1);
    }

    @Test
    void 回填失败不影响本次返回() {
        when(valueOps.get(ItemCache.KEY)).thenReturn(null);
        when(itemMapper.selectList(null)).thenReturn(List.of(hotDog()));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOps).set(eq(ItemCache.KEY), anyString());

        List<Item> items = cache.getAll();

        assertThat(items).hasSize(1);
    }

    @Test
    void 缓存里的内容解不出来时回源() {
        // 兼容性事故的典型形态：内容格式换过一次、或者有人手工往 key 里塞了别的东西
        when(valueOps.get(ItemCache.KEY)).thenReturn("这不是 JSON");
        when(itemMapper.selectList(null)).thenReturn(List.of(hotDog()));

        List<Item> items = cache.getAll();

        assertThat(items).hasSize(1);
    }

    // ── 失效 ────────────────────────────────────────────────────────────────

    @Test
    void 失效删掉的就是那一个key() {
        cache.evict();

        verify(redis).delete(ItemCache.KEY);
    }

    @Test
    void Redis连不上时失效不抛异常() {
        when(redis.delete(ItemCache.KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> cache.evict()).doesNotThrowAnyException();
    }

    // ── 预热 ────────────────────────────────────────────────────────────────

    @Test
    void 预热把全量条目灌进key() {
        when(valueOps.get(ItemCache.KEY)).thenReturn(null);
        when(itemMapper.selectList(null)).thenReturn(List.of(hotDog(), firstAidKit()));

        cache.warmUp();

        // 断言"写进去的那串 JSON 能解回 2 条"，而不是只断言 set 被调用过：
        // 后者在"写了个空数组"时照样通过
        var written = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(ItemCache.KEY), written.capture());
        assertThat(cache.deserialize(written.getValue())).hasSize(2);
    }

    @Test
    void Redis连不上时预热不抛异常() {
        when(valueOps.get(ItemCache.KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(itemMapper.selectList(null)).thenReturn(List.of(hotDog()));

        assertThatCode(() -> cache.warmUp()).doesNotThrowAnyException();
    }

    @Test
    void MySQL也连不上时预热不抛异常() {
        // 分工：getAll 只保证"缓存的问题不把孩子带崩"，回源失败仍会冒出来；
        // 兜住它的是 StartupWarmUp（@Order(HIGHEST_PRECEDENCE) 的那个 runner），
        // 否则第一次预热的失败会把整个应用启动带下去 —— 演示环境的 Redis / MySQL 启动顺序都没保证
        when(itemMapper.selectList(null)).thenThrow(new IllegalStateException("datasource not ready"));

        assertThatCode(() -> new StartupWarmUp(cache).run(null)).doesNotThrowAnyException();
    }

    // ── 测试数据 ────────────────────────────────────────────────────────────

    private static Item hotDog() {
        Item item = new Item();
        item.setId(1L);
        item.setNameEn("Hot Dog");
        item.setNameZh("热狗肠");
        item.setWeight(5.0f);
        item.setIcon("/icons/Hot_Dog.png");
        item.setDescription("Hot Dog is a natural food");
        item.setDescriptionZh("热狗肠是 PEAK 中的天然食物");
        item.setTag(new ArrayList<>(List.of(
                tag("type", "Food"),
                tag("flag", "cookable"))));
        item.setEffect(new ArrayList<>(List.of(
                effect("HUNGER", -30.0f, null, null),
                effect("HUNGER_COOKED", -60.0f, null, null))));
        return item;
    }

    private static Item firstAidKit() {
        Item item = new Item();
        item.setId(2L);
        item.setNameEn("First Aid Kit");
        item.setNameZh("急救包");
        item.setWeight(5.0f);
        item.setTag(new ArrayList<>(List.of(tag("type", "Consumable"))));
        return item;
    }

    private static ItemTag tag(String code, String value) {
        ItemTag t = new ItemTag();
        t.setCode(code);
        t.setValue(value);
        t.setNameZh("中文名");
        return t;
    }

    private static Effect effect(String code, Float value, Float duration, Float startDelay) {
        Effect e = new Effect();
        e.setCode(code);
        e.setValue(value);
        e.setDuration(duration);
        e.setStartDelay(startDelay);
        return e;
    }
}
