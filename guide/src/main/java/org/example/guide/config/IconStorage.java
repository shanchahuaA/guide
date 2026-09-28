package org.example.guide.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图标存在哪、以及它对外长什么 URL。
 *
 * 目录默认是后端进程工作目录下的 {@code icons/}(用 {@code mvn spring-boot:run} 从 guide/ 启动
 * 就是 {@code guide/icons/})。**该目录随仓库发布**(见 #24):克隆下来就有一份图标,
 * 采集跑起来按同样的文件名覆盖它们。
 *
 * <p>写盘的一方是应用**外面**的采集脚本({@code guide/tools/item_icons.py},
 * 目录由 {@code GUIDE_ICON_DIR} 指定),对外暴露的一方是 {@code WebMvcConfig} 的静态资源映射。
 * 两边各写一份默认值迟早会对不上,那种故障是"文件写到了一个地方、HTTP 去另一个地方找",最难查,
 * 所以这里的默认值就是采集脚本的默认值。
 */
@Component
public class IconStorage {

    private final Path directory;
    private final String urlPrefix;

    public IconStorage(@Value("${guide.icon-dir:icons}") String iconDir,
                       @Value("${guide.icon-url-prefix:/icons}") String urlPrefix) {
        this.directory = Paths.get(iconDir).toAbsolutePath().normalize();
        this.urlPrefix = normalizeUrlPrefix(urlPrefix);
    }

    /** 图标对外的 URL 前缀,如 {@code /icons} */
    public String urlPrefix() {
        return urlPrefix;
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
