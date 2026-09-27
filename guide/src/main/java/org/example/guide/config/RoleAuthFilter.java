package org.example.guide.config;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.web.filter.authz.RolesAuthorizationFilter;

import java.io.IOException;

/**
 * 分级门禁过滤器：判定链里挂的角色（如 {@code roleAuthc[beginner]}）不满足时，
 * 回 HTTP 403 + 响应壳（code=403），而不是 Shiro 默认的跳转/空 401 —— 前端只在
 * 401 时清登录态，等级不足不应把用户弹回登录页。
 *
 * <p>与 {@link TokenAuthFilter} 同挂在 Shiro 过滤链上（见 ShiroConfig）。
 */
public class RoleAuthFilter extends RolesAuthorizationFilter {

    private static final String FORBIDDEN_JSON =
            "{\"success\":false,\"code\":403,\"message\":\"当前等级不足\",\"data\":null}";

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws IOException {
        HttpServletResponse http = (HttpServletResponse) response;
        http.setStatus(403);
        http.setCharacterEncoding("UTF-8");
        http.setContentType("application/json;charset=UTF-8");
        http.getWriter().write(FORBIDDEN_JSON);
        return false;
    }
}
