package org.example.guide.config;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authc.AuthenticationFilter;

import java.io.IOException;

/**
 * 无状态登录过滤器：从请求头 {@code token} 取 openid → {@link Subject#login}。
 * 前端已登录则直接放行；没带 token 或查不到人，回 HTTP 401 + 响应壳（code=401），
 * 前端据此清登录态并重新静默登录。
 *
 * <p>只挂在 Shiro 过滤链上，不注册为独立 Servlet Filter（在 ShiroConfig 的工厂方法里
 * new 出来塞进 filters 表），否则 Spring Boot 会把它自动注册到 {@code /*}，连登录接口一起拦。
 */
public class TokenAuthFilter extends AuthenticationFilter {

    /** 响应壳与 BaseResult 一致，字段顺序稳定，message 为固定中文文案，无需转义 */
    private static final String UNAUTHORIZED_JSON =
            "{\"success\":false,\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}";

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        Subject subject = getSubject(request, response);
        if (subject.isAuthenticated()) {
            return true;
        }
        String token = ((HttpServletRequest) request).getHeader("token");
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            subject.login(new OpenidToken(token));
            return true;
        } catch (AuthenticationException e) {
            return false;
        }
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws IOException {
        HttpServletResponse http = (HttpServletResponse) response;
        http.setStatus(401);
        http.setCharacterEncoding("UTF-8");
        http.setContentType("application/json;charset=UTF-8");
        http.getWriter().write(UNAUTHORIZED_JSON);
        return false;
    }
}
