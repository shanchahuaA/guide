package org.example.guide.crawler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图标存在哪、以及它对外长什么 URL。
 *
 * 目录默认是后端进程工作目录下的 {@code icons/}(用 {@code mvn spring-boot:run} 从 guide/ 启动
 * 就是 {@code guide/icons/}),该目录进 .gitignore —— 图标是二进制,采集随时能再生成一份。
 *
 * <p>写盘的一方({@link ItemIconDownloader})和对外暴露的一方
 * ({@code WebMvcConfig} 的静态资源映射)都从这里取路径。两边各写一份默认值迟早会对不上,
 * 那种故障是"文件写到了一个地方、HTTP 去另一个地方找",最难查。
 */
@Component
public class IconStorage {

    private final Path directory;
    private final String urlPrefix;

    public IconStorage(@Value("${guide.crawler.icon-dir:icons}") String iconDir,
                       @Value("${guide.crawler.icon-url-prefix:/icons}") String urlPrefix) {
        this.directory = Paths.get(iconDir).toAbsolutePath().normalize();
        this.urlPrefix = normalizeUrlPrefix(urlPrefix);
    }

    /** 图标落盘目录的绝对路径 */
    public Path directory() {
        return directory;
    }

    /** 图标对外的 URL 前缀,如 {@code /icons} */
    public String urlPrefix() {
        return urlPrefix;
    }

    /** 条目 {@code icon} 列里存的相对路径,如 {@code /icons/Hot_Dog.png} */
    public String urlFor(String fileName) {
        return urlPrefix + "/" + fileName;
    }

    /**
     * Spring 静态资源映射要的 location。
     *
     * 必须是 URI 形式且以 {@code /} 结尾:少了这个斜杠,注册进去的会被当成"某个文件"
     * 而不是"某个目录",于是所有图标 404。
     */
    public String resourceLocation() {
        String uri = directory.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    /** 容忍配置里写成 {@code icons} / {@code /icons/} 这类等价写法,统一成 {@code /icons} */
    private static String normalizeUrlPrefix(String raw) {
        String prefix = raw == null ? "" : raw.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix.isEmpty() ? "/icons" : prefix;
    }
}
