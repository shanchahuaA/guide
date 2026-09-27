package org.example.guide.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 大模型调用用的 {@link RestClient}。
 *
 * <p><b>为什么自己建一个而不是注入 Spring Boot 的 {@code RestClient.Builder} 自动配置</b>：
 * 本应用没有引入 {@code spring-boot-starter-restclient} 之类的自动配置，
 * 容器里根本没有那个 builder —— 注入它会得到一个"启动时 NoSuchBeanDefinition"的失败，
 * 而不是编译期错误。这里显式建，依赖面反而更小、更看得见。
 *
 * <p>超时是必须设的：不设时底层用 JDK 的默认值（连接与读取都是无限等）。
 * 大模型那边卡住时，用户的提问会一直挂在页面上、连接也一直被占着，
 * 而"AI 服务连不上"正常就该在半分钟内告诉用户。
 */
@Configuration
public class RestClientConfig {

    /** 建连超时。DNS 挂了 / 端口不通时要多久放弃 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 读取超时。比建连长得多 —— 大模型要想几秒到几十秒才吐出整包响应
     * （本类不走流式，等的是完整响应）。
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }
}
