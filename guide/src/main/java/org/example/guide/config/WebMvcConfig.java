package org.example.guide.config;

import org.example.guide.crawler.IconStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 图标的静态资源映射:把 {@code /icons/**} 指到后端本地的图标目录。
 *
 * 小程序因此不外链数据源(wiki 域名要备案,而且会被 Cloudflare 拦),
 * 图标和接口走同一个 origin,开发者工具里只需勾一次"不校验合法域名"。
 *
 * <p>Shiro 侧不用动:过滤链现在是 {@code /** = anon}(见 ShiroConfig),
 * {@code /icons/**} 本来就匿名可访问。等后台登录把 {@code /admin/**} 收口时要注意别连图标一起收进去 ——
 * 图标是小程序要匿名拉的。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final IconStorage iconStorage;

    public WebMvcConfig(IconStorage iconStorage) {
        this.iconStorage = iconStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(iconStorage.urlPrefix() + "/**")
                .addResourceLocations(iconStorage.resourceLocation());
    }
}
