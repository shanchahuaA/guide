package org.example.guide.config;

import org.apache.shiro.authc.AuthenticationToken;

/**
 * 无密码登录令牌：承载 openid（即前端传的 token 头）。principal 与 credentials 都是同一个 openid，
 * Realm 里"按 openid 查到人即通过"，CredentialsMatcher 恒成立。
 */
public class OpenidToken implements AuthenticationToken {

    private final String openid;

    public OpenidToken(String openid) {
        this.openid = openid;
    }

    @Override
    public Object getPrincipal() {
        return openid;
    }

    @Override
    public Object getCredentials() {
        return openid;
    }
}
