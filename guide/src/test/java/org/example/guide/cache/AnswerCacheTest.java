package org.example.guide.cache;

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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnswerCache} 的行为测试。**不连真 Redis** ——
 * 真 Redis 上的整链路由 {@code scripts/smoke-test.ps1} 的问答那一组覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerCacheTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AnswerCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new AnswerCache(redis);
    }

    @Test
    void key落在answer前缀下且由问题文本决定() {
        String key = AnswerCache.keyOf("蘑菇有什么用");

        assertThat(key).startsWith(AnswerCache.KEY_PREFIX);
        // 同一个问题必须落到同一个 key，否则缓存永远不命中
        assertThat(AnswerCache.keyOf("蘑菇有什么用")).isEqualTo(key);
        assertThat(AnswerCache.keyOf("蘑菇怎么用")).isNotEqualTo(key);
    }

    @Test
    void key是纯ASCII摘要_便于redis_cli直接查看与删除() {
        // 问题文本含空格与中文；原文直接当 key 时 redis-cli KEYS/GET 读不回来
        String key = AnswerCache.keyOf("蘑菇有什么用");

        assertThat(key).matches("guide:answer:[0-9a-f]{64}");
    }

    @Test
    void 写入与读取用的是同一个key() {
        cache.put("蘑菇有什么用", "答案是……");

        verify(valueOps).set(eq(AnswerCache.keyOf("蘑菇有什么用")), eq("答案是……"));

        when(valueOps.get(AnswerCache.keyOf("蘑菇有什么用"))).thenReturn("答案是……");
        assertThat(cache.get("蘑菇有什么用")).isEqualTo("答案是……");
    }

    @Test
    void 缓存不设TTL() {
        cache.put("蘑菇有什么用", "答案");

        // 回答的正确性只取决于图鉴数据，图鉴变了走 evictAll（采集跑完），不靠过期。
        // 用 expire(K, Expiration) 那个重载来断言：两个重载都可能被误用，这一条挡的是"有人补了个过期时间"
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    void Redis读失败时返回null而不是上抛() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));

        // 与 ItemCache 同一套容错：Redis 挂了不该让问答整个不可用，读不到就去调大模型
        assertThat(cache.get("蘑菇有什么用")).isNull();
    }

    @Test
    void Redis写失败不影响本次回答() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOps).set(anyString(), anyString());

        // 这一轮的回答已经拿到了，不能因为"写不进缓存"把它变成失败
        assertThatCode(() -> cache.put("蘑菇有什么用", "答案")).doesNotThrowAnyException();
    }

    @Test
    void 失效删掉前缀下的全部key() {
        when(redis.keys(AnswerCache.KEY_PREFIX + "*"))
                .thenReturn(Set.of(AnswerCache.keyOf("问题一"), AnswerCache.keyOf("问题二")));

        cache.evictAll();

        verify(redis).delete(Set.of(AnswerCache.keyOf("问题一"), AnswerCache.keyOf("问题二")));
    }

    @Test
    void 没有缓存时不发DEL() {
        when(redis.keys(anyString())).thenReturn(Set.of());

        assertThatCode(() -> cache.evictAll()).doesNotThrowAnyException();

        verify(redis, never()).delete(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void 空问题不写入缓存() {
        cache.put("", "答案");
        cache.put("蘑菇有什么用", "  ");

        // 空 key 与空答案是"没东西可缓存"，写进去只会让下次读出来一个空回答
        verify(valueOps, never()).set(anyString(), anyString());
    }
}
