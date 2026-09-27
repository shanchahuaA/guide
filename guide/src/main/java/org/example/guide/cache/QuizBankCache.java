package org.example.guide.cache;

import org.example.guide.pojo.QuizQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 三级题库的缓存（#42）。**照 {@link ItemCache} 的范式写**：吞掉缓存异常只记日志、
 * key 名只有一个公开常量、出口是原始模型而不是 DTO。
 *
 * <p>每个等级一个 key（{@value #KEY_PREFIX} + 等级），因为题库是**懒生成**的 ——
 * 第一次有人要某级的题时才生成那一级，之后复用。三级共用一个 key 会让"只生成了一级"
 * 变成"三级都得先有"，懒生成就没了意义。
 *
 * <p>与图鉴缓存不同，这里**没有回源**：题库的"源"是大模型，不是数据库，
 * 读不到就让上层去生成（见 {@code QuizServiceImpl.next}）。缓存组件只负责存取与失效。
 *
 * <p>不设 TTL：题库的正确性只取决于图鉴数据，图鉴变了走 {@link #evictAll()}
 * （采集跑完，与图鉴 key 一起删）。
 */
@Component
public class QuizBankCache {

    private static final Logger log = LoggerFactory.getLogger(QuizBankCache.class);

    /** 题库缓存的 key 前缀。除了本类，验收（redis-cli）也用它 */
    public static final String KEY_PREFIX = "guide:quiz:bank:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 反序列化目标 {@code List<QuizQuestion>}。显式构造，别让它读回来变成一堆 Map */
    private final JavaType listType;

    public QuizBankCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.listType = objectMapper.getTypeFactory().constructCollectionType(List.class, QuizQuestion.class);
    }

    /** 某一级的题库；key 不在 / Redis 挂了 / 内容解不出来都返回 null，由调用方决定要不要重新生成 */
    public List<QuizQuestion> get(int level) {
        try {
            String raw = redis.opsForValue().get(keyOf(level));
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(raw, listType);
        } catch (Exception e) {
            log.warn("读题库缓存失败，将重新生成：level={}", level, e);
            return null;
        }
    }

    /** 写入某一级的题库。失败只记日志 —— 写不进缓存只影响命中率，不影响这次已生成的题 */
    public void put(int level, List<QuizQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return;
        }
        try {
            redis.opsForValue().set(keyOf(level), objectMapper.writeValueAsString(questions));
        } catch (Exception e) {
            log.warn("写题库缓存失败（不影响本次出题）：level={}", level, e);
        }
    }

    /**
     * 删掉全部等级题库。采集跑完调它（{@code CrawlerServiceImpl}）—— 题面依据的是图鉴数据，
     * 图鉴换了旧题库里的题就可能问到一个已经改过的数值。
     */
    public void evictAll() {
        try {
            var keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            log.info("题库缓存已失效：{} 条", keys == null ? 0 : keys.size());
        } catch (Exception e) {
            log.warn("题库缓存失效失败", e);
        }
    }

    /** 缓存 key：前缀 + 等级 */
    public static String keyOf(int level) {
        return KEY_PREFIX + level;
    }
}
