package org.example.guide.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 用户头像存在哪、对外长什么 URL。
 *
 * <p>与 {@code crawler/IconStorage} 同一个思路：**写盘的一方**（{@code TeachController} 收上传）
 * 和**对外暴露的一方**（{@link WebMvcConfig} 的静态资源映射）共用这一份路径，
 * 免得两边各写一份默认值、最后对不上（"文件写到 A、HTTP 去 B 找"最难查）。
 *
 * <p>目录默认是后端进程工作目录下的 {@code avatars/}（从 {@code guide/} 启动即 {@code guide/avatars/}），
 * 该目录进 .gitignore —— 用户自己传的二进制，随时能覆盖一份。
 */
@Component
public class AvatarStorage {

    private final Path directory;
    private final String urlPrefix;

    public AvatarStorage(@Value("${guide.avatar-dir:avatars}") String avatarDir,
                         @Value("${guide.avatar-url-prefix:/avatars}") String urlPrefix) {
        this.directory = Paths.get(avatarDir).toAbsolutePath().normalize();
        this.urlPrefix = normalizeUrlPrefix(urlPrefix);
    }

    /** 头像落盘目录的绝对路径 */
    public Path directory() {
        return directory;
    }

    /** 头像对外的 URL 前缀，如 {@code /avatars} */
    public String urlPrefix() {
        return urlPrefix;
    }

    /** 某个文件名对外的 URL，如 {@code /avatars/oXXXX.png}。写进 {@code user.avatar} 列的就是它 */
    public String urlFor(String fileName) {
        return urlPrefix + "/" + fileName;
    }

    /**
     * Spring 静态资源映射要的 location。
     *
     * <p>必须是 URI 形式且以 {@code /} 结尾 —— 少这个斜杠会被当成"某个文件"而不是"某个目录"，
     * 于是所有头像 404（与 {@code IconStorage} 同一个坑）。
     */
    public String resourceLocation() {
        String uri = directory.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    /**
     * 把 openid 收敛成能安全当文件名的样子。
     *
     * <p>openid 正常是 {@code [A-Za-z0-9_-]}，但它是**外部输入**，直接拼进路径就可能被
     * {@code ../} 穿出去 —— 这里只留白名单字符，其余换成下划线。
     */
    public static String safeFileName(String openid) {
        String safe = openid == null ? "" : openid.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isEmpty() ? "anonymous" : safe;
    }

    /** 容忍配置里写成 {@code avatars} / {@code /avatars/} 这类等价写法，统一成 {@code /avatars} */
    private static String normalizeUrlPrefix(String raw) {
        String prefix = raw == null ? "" : raw.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix.isEmpty() ? "/avatars" : prefix;
    }
}
