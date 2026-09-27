package org.example.guide.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * AI 问答的回答缓存（契约 §7.4「成功的回答缓存进 Redis，key 用问题文本」、§9.2）。
 *
 * <p>key 的口径是**问题文本** —— 缓存命中的判据就是"同一个问题问第二次"，
 * 不做归一化（去掉空格、大小写折叠之类）：那会让"蘑菇有什么用"和"蘑菇有什么用 "算同一个问题，
 * 看起来是好事，实际是把一个语义问题塞进字符串处理里，规则一旦不对就是答非所问。
 *
 * <p>{@code guide:answer:} 之后跟的是问题文本的 **SHA-256 十六进制摘要**，不是问题原文。
 * 缓存里存什么、key 叫什么，从接口上完全看不出来，所以这里可以自由选形态；
 * 选摘要是因为原文作 key 有两个实际麻烦：含空格与中文时 {@code redis-cli KEYS *} 读不回来，
 * 而且问题长度没有上限，会顶到 Redis 的 key 长度限制。
 *
 * <p>摘要本身已经是 {@code [0-9a-f]} 的定长串，所以这里**不需要**再套一层字符白名单
 * （{@code IconFileNames.sanitize} 之类）—— 那是给"用户可控的原始文本要当文件名"用的。
 */
@Component
public class AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

    /** 回答缓存的 key 前缀。除了本类，只有验收（redis-cli）会用到它 */
    public static final String KEY_PREFIX = "guide:answer:";

    private final StringRedisTemplate redis;

    public AnswerCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 取缓存的回答。
     *
     * <p>与 {@link ItemCache} 同一套容错：**Redis 的任何问题都不上抛**，
     * 读不到就返回 null，调用方照常去调大模型 —— Redis 挂了不该让问答整个不可用。
     */
    public String get(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        try {
            return redis.opsForValue().get(keyOf(question));
        } catch (Exception e) {
            log.warn("读问答缓存失败，将回落到真调大模型：question={}", question, e);
            return null;
        }
    }

    /**
     * 缓存一条成功的回答。失败只记日志 —— 这一轮的回答已经拿到了，
     * 不能因为"写不进缓存"把一次成功的问答变成失败（与 {@code ItemCache} 回填同一口径）。
     *
     * <p>不设 TTL：回答的正确性只取决于图鉴数据，而图鉴变了会走 {@link #evictAll()}（采集跑完）。
     */
    public void put(String question, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(keyOf(question), answer);
        } catch (Exception e) {
            log.warn("写问答缓存失败（不影响本次回答）：question={}", question, e);
        }
    }

    /**
     * 删掉全部回答缓存。采集跑完调它 —— 回答的依据是图鉴数据，图鉴变了旧回答就该作废
     * （与 {@code ItemCache.evict()} 同一个理由，见 CONTEXT.md「缓存」）。
     *
     * <p>用 {@code KEYS + DEL} 而不是 {@code SCAN}：演示环境里问答量是几十条的量级，
     * {@code KEYS} 的代价可以忽略，换来的是一次调用就删干净。
     */
    public void evictAll() {
        try {
            var keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            log.info("问答缓存已失效：{} 条", keys == null ? 0 : keys.size());
        } catch (Exception e) {
            log.warn("问答缓存失效失败", e);
        }
    }

    /** 缓存 key：前缀 + 问题文本的 SHA-256 摘要 */
    public static String keyOf(String question) {
        return KEY_PREFIX + sha256Hex(question);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必带算法，走不到这里；真走到了也不该把一个"取 key"的动作变成 500
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
