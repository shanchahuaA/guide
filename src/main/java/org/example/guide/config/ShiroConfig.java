package org.example.guide.config;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shiro 配置
 * shiro-spring-boot-web-starter 启动时强制要求容器中至少存在一个 Realm Bean
 */
@Configuration
public class ShiroConfig {

    @Bean
    public Realm adminRealm() {
        return new AdminRealm();
    }

    /**
     * 过滤链规则：开发阶段全部放行（anon），避免 starter 默认拦截导致的重定向循环
     * TODO 后台登录做好后改为：/admin/** 走 authc，其余接口走 anon
     */
    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/**", "anon");
        return chain;
    }

    /**
     * 自定义 Realm：负责认证（登录校验）和授权（角色/权限）
     */
    static class AdminRealm extends AuthorizingRealm {

        /**
         * 授权：返回当前用户拥有的角色和权限
         * 后续管理员/角色表建好后在此填充
         */
        @Override
        protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
            return new SimpleAuthorizationInfo();
        }

        /**
         * 认证：校验用户名和密码
         */
        @Override
        protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
            UsernamePasswordToken upToken = (UsernamePasswordToken) token;
            String username = upToken.getUsername();

            // TODO 管理员表建好后改为：按 username 查库，查不到抛 UnknownAccountException，
            //  查到后把数据库中的加密密码交给 CredentialsMatcher 比对
            // 当前为开发阶段占位实现：直接把提交的密码作为正确凭证，任意账号均可登录
            String password = new String(upToken.getPassword());
            return new SimpleAuthenticationInfo(username, password, getName());
        }
    }
}
