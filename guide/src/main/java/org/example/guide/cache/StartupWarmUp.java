package org.example.guide.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动预热：应用起来后把全量图鉴灌进 Redis（CONTEXT.md 缓存节）。
 *
 * <p>预热与"谁在什么时候登录"没有因果关系 —— 图鉴是纯查询工具，用户不一定登录，
 * 所以触发时机只能是**应用启动**，而不是第一次请求。
 *
 * <p><b>失败只记日志、不挡启动</b>：演示环境的 Redis 启动顺序没有保证（先起后端再起 Redis 是常态）。
 * {@link ItemCache#getAll()} 已经把"缓存本身"的异常吞在内部了，但回源 MySQL 失败仍会冒出来 ——
 * 这里的 try/catch 就是那道拦网：图鉴取不到时应用该照常起来（起来后 MySQL 通了请求自然会成功），
 * 而不是整个启动失败。这也正是契约 §8.3 说的"预热包 try/catch 只 log.warn"。
 *
 * <p>排在前面（{@link Ordered#HIGHEST_PRECEDENCE}）：它只读写缓存、不依赖别的 runner 的产物，
 * 早跑完就意味着"应用 ready 时 key 已经在了"，冒烟脚本与小程序开发者工具都不用等。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupWarmUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmUp.class);

    private final ItemCache itemCache;

    public StartupWarmUp(ItemCache itemCache) {
        this.itemCache = itemCache;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            itemCache.warmUp();
        } catch (Exception e) {
            // 连不上只记日志：接口照常走回源 MySQL 那条路，不因为预热失败拒绝启动
            log.warn("图鉴缓存预热失败，接口将走回源 MySQL：Redis 或 MySQL 可能还没起来", e);
        }
    }
}
