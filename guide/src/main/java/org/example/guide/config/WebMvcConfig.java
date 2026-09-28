package org.example.guide.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本地静态资源的映射:把 {@code /icons/**} 指到图标目录、{@code /avatars/**} 指到用户头像目录。
 *
 * 小程序因此不外链数据源(wiki 域名要备案,而且会被 Cloudflare 拦),
 * 图标/头像和接口走同一个 origin,开发者工具里只需勾一次"不校验合法域名"。
 *
 * <p>两处都落在 Shiro 过滤链 {@code /**} 的兜底规则上,匿名可访问(过滤链见 ShiroConfig) ——
 * 图标和头像是小程序要匿名拉的,将来收口任何后台路径时别把它们一起收进去。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final IconStorage iconStorage;
    private final AvatarStorage avatarStorage;

    public WebMvcConfig(IconStorage iconStorage, AvatarStorage avatarStorage) {
        this.iconStorage = iconStorage;
        this.avatarStorage = avatarStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(iconStorage.urlPrefix() + "/**")
                .addResourceLocations(iconStorage.resourceLocation());
        registry.addResourceHandler(avatarStorage.urlPrefix() + "/**")
                .addResourceLocations(avatarStorage.resourceLocation());
    }
}
