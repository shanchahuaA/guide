package org.example.guide.config;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信小程序登录用的 {@link WxMaService}。
 *
 * <p>appid / secret 从配置读：仓库里的 {@code application.yml} 只给空占位，
 * 真值放已 gitignore 的 {@code application-local.yml}（键名 guide.wechat.appid / secret）。
 * 两个键为空时本 Bean 照样建得出来 —— 换 openid 的失败留到 {@code /api/auth/login} 调用时报，
 * 不在这里挡住整个应用启动（只做登录、没有别的启动期用途）。
 */
@Configuration
public class WxMaConfig {

    @Bean
    public WxMaService wxMaService(@Value("${guide.wechat.appid:}") String appid,
                                   @Value("${guide.wechat.secret:}") String secret) {
        WxMaDefaultConfigImpl config = new WxMaDefaultConfigImpl();
        config.setAppid(appid);
        config.setSecret(secret);
        WxMaService service = new WxMaServiceImpl();
        service.setWxMaConfig(config);
        return service;
    }
}
