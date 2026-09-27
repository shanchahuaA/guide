package org.example.guide.cache;

import org.example.guide.pojo.QuizProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * 连对进度缓存（#42）：一个用户一个 key，存 {@code streak} 与排除集，**TTL 2 小时**。
 *
 * <p>TTL 是票面口径：连对是一个"坐下来练一会儿"的状态，搁置两小时就当没练过，
 * 不至于让一个三天前的连对进度还挂在等级条上。它和题库不同 —— 题库没有 TTL，
 * 因为题库是内容、可以被复用；连对是过程状态、过期即作废。
 *
 * <p>与其它缓存同一套容错：**Redis 的任何异常都不上抛**。读不到当空进度（连对 0、无排除集），
 * 写不进只记日志 —— 一次答题的判对与否不受缓存状态影响。
 */
@Component
public class QuizProgressCache {

    private static final Logger log = LoggerFactory.getLogger(QuizProgressCache.class);

    /** 连对进度的 key 前缀。除了本类，验收（redis-cli）也用它 */
    public static final String KEY_PREFIX = "guide:quiz:progress:";

    /** 连对状态的存活时间（票面 AC：2 小时） */
    public static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public QuizProgressCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 当前进度；没有缓存 / 读失败一律当空进度，调用方不必处理 null */
    public QuizProgress get(String openid) {
        if (openid == null || openid.isBlank()) {
            return QuizProgress.empty();
        }
        try {
            String raw = redis.opsForValue().get(keyOf(openid));
            if (raw == null) {
                return QuizProgress.empty();
            }
            QuizProgress progress = objectMapper.readValue(raw, QuizProgress.class);
            return progress == null ? QuizProgress.empty() : progress;
        } catch (Exception e) {
            log.warn("读连对缓存失败，按空进度处理：openid={}", openid, e);
            return QuizProgress.empty();
        }
    }

    /** 写回进度并**重设** 2 小时 TTL：每次答题都把过期时间往后推，连续练习期间不会中途失效 */
    public void put(String openid, QuizProgress progress) {
        if (openid == null || openid.isBlank() || progress == null) {
            return;
        }
        try {
            redis.opsForValue().set(keyOf(openid), objectMapper.writeValueAsString(progress), TTL);
        } catch (Exception e) {
            log.warn("写连对缓存失败：openid={}", openid, e);
        }
    }

    /** 缓存 key：前缀 + openid（openid 是 ASCII，直接当 key，方便 redis-cli 观察） */
    public static String keyOf(String openid) {
        return KEY_PREFIX + openid;
    }
}
