package org.example.guide.config;

import jakarta.servlet.Filter;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.web.config.ShiroFilterConfiguration;
import org.apache.shiro.web.filter.mgt.DefaultFilter;
import org.example.guide.pojo.User;
import org.example.guide.service.IUserService;
import org.example.guide.service.UserLevels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shiro 配置：微信小程序的无状态登录 + 分级授权。
 *
 * <p><b>认证</b>：前端把 openid 当 token 放在请求头 {@code token}，{@link TokenAuthFilter}
 * 拿它 {@code Subject.login(new OpenidToken(openid))}，{@link AdminRealm} 按 openid 查
 * user 表，查到即通过。
 *
 * <p><b>授权</b>：角色由等级映射 —— 0 菜鸟=[rookie]，1 入门=[rookie,beginner]，
 * 2 高手=[rookie,beginner,expert]（高手通吃三级）。过滤链里
 * {@code /api/teach/beginner/**} 要 beginner，{@code /api/teach/expert/**} 要 expert。
 *
 * <p><b>自定义过滤器不入 Spring 容器</b>：两个过滤器是普通对象，在 {@link #shiroFilterFactoryBean}
 * 工厂方法里直接 new 出来塞进 filters 表。不注册成 Bean 有两条好处 ——
 * Spring Boot 4 不会把"所有 Filter Bean"自动注册到 {@code /*}（否则登录接口也会被拦）；
 * 也不会被 starter 的 {@code filterMap()}（收集容器内 Filter Bean）和
 * {@code ShiroFilterFactoryBeanPostProcessor} 提前处理而踩中初始化顺序问题。
 */
@Configuration
public class ShiroConfig {

    @Bean
    public Realm adminRealm(IUserService userService) {
        return new AdminRealm(userService);
    }

    /**
     * 自定义过滤器工厂 Bean，完全顶替 starter autoconfig 的同名方法（autoconfig 带
     * {@code @ConditionalOnMissingBean}）。逻辑与 {@code AbstractShiroWebFilterConfiguration}
     * 的默认实现一致，只是把 {@code filterMap()}（收集容器内 Filter Bean）换成我们自己的
     * 两个过滤器：{@code tokenAuthc} 认 header token，{@code roleAuthc} 按角色放行。
     *
     * <p>必须由我们接管而不是改 starter 的字段注入：starter 的工厂方法是
     * {@code @Autowired} 字段读 {@code shiroFilterChainDefinition}，任何"提前创建工厂 Bean"
     * 的旁路（比如 post-processor 参数注入）都会撞上字段尚未注入的 NPE。
     */
    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(
            SecurityManager securityManager,
            ShiroFilterChainDefinition shiroFilterChainDefinition) {
        ShiroFilterFactoryBean bean = new ShiroFilterFactoryBean();
        bean.setSecurityManager(securityManager);
        bean.setShiroFilterConfiguration(new ShiroFilterConfiguration());
        // 与 autoconfig 默认一致：全局挂上 invalidRequest 过滤器
        bean.setGlobalFilters(Collections.singletonList(DefaultFilter.invalidRequest.name()));
        bean.setFilterChainDefinitionMap(shiroFilterChainDefinition.getFilterChainMap());

        Map<String, Filter> filters = new LinkedHashMap<>();
        filters.put("tokenAuthc", new TokenAuthFilter());
        filters.put("roleAuthc", new RoleAuthFilter());
        bean.setFilters(filters);
        return bean;
    }

    /**
     * 过滤链规则（首匹配生效，具体路径在前）：
     * <ul>
     *   <li>登录、图鉴三件套 —— anon</li>
     *   <li>分级内容 —— tokenAuthc, roleAuthc[角色]</li>
     *   <li>教学其余端点 —— tokenAuthc（登录即可）</li>
     *   <li>其余（/icons/**、演示后门等）—— anon</li>
     * </ul>
     */
    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/auth/login", "anon");
        chain.addPathDefinition("/api/items", "anon");
        chain.addPathDefinition("/api/items/**", "anon");
        chain.addPathDefinition("/api/tags", "anon");
        chain.addPathDefinition("/api/biomes", "anon");
        chain.addPathDefinition("/api/teach/expert/**", "tokenAuthc, roleAuthc[expert]");
        chain.addPathDefinition("/api/teach/beginner/**", "tokenAuthc, roleAuthc[beginner]");
        chain.addPathDefinition("/api/teach/**", "tokenAuthc");
        chain.addPathDefinition("/**", "anon");
        return chain;
    }

    /**
     * 自定义 Realm：认证=按 openid 查 user 表；授权=等级映射成角色集合。
     * 缓存全关：token 即 openid、无状态，每次请求现查库；跳级后门改 level 立即生效，不用清缓存。
     */
    static class AdminRealm extends AuthorizingRealm {

        private static final String ROLE_ROOKIE = "rookie";
        private static final String ROLE_BEGINNER = "beginner";
        private static final String ROLE_EXPERT = "expert";

        private final IUserService userService;

        AdminRealm(IUserService userService) {
            this.userService = userService;
            setAuthenticationCachingEnabled(false);
            setAuthorizationCachingEnabled(false);
        }

        @Override
        public boolean supports(AuthenticationToken token) {
            return token instanceof OpenidToken;
        }

        /** 授权：按等级给角色 —— 0:[rookie]，1:+[beginner]，2:+[expert] */
        @Override
        protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
            String openid = (String) principals.getPrimaryPrincipal();
            User user = userService.findByOpenid(openid);
            SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
            Integer level = user == null ? null : user.getLevel();
            if (level == null) {
                return info;
            }
            info.addRole(ROLE_ROOKIE);
            if (level >= UserLevels.BEGINNER) {
                info.addRole(ROLE_BEGINNER);
            }
            if (level >= UserLevels.EXPERT) {
                info.addRole(ROLE_EXPERT);
            }
            return info;
        }

        /** 认证：查得到人就通过（token 即 openid，无密码比对） */
        @Override
        protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
            String openid = (String) token.getPrincipal();
            User user = userService.findByOpenid(openid);
            if (user == null) {
                throw new UnknownAccountException("openid 不存在：" + openid);
            }
            return new SimpleAuthenticationInfo(openid, openid, getName());
        }
    }
}
