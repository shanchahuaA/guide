package org.example.guide.controller;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.example.guide.pojo.User;
import org.example.guide.pojo.dto.LoginDto;
import org.example.guide.service.IUserService;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录最小版（契约 §6）：收 {@code js_code}，后端带 appid + secret 调 code2Session 换 openid，
 * 按 openid 建或取用户行，回 openid + 占位 token + 当前等级。
 *
 * <p>Shiro 过滤链、JWT 签发与 Realm 仍不做（留给 #5）—— 本类只落登录本身。
 */
@Slf4j
@RestController
public class AuthController {

    @Autowired
    private WxMaService wxMaService;
    @Autowired
    private IUserService userService;

    @PostMapping("/api/auth/login")
    public BaseResult login(@RequestBody Map<String, String> body) {
        String jsCode = body == null ? null : body.get("js_code");
        if (jsCode == null || jsCode.isEmpty()) {
            BaseResult bad = BaseResult.setResult(ResultCodeEnum.PARAM_ERROR, null);
            bad.setMessage("缺少 js_code");
            return bad;
        }

        String openid;
        try {
            WxMaJscode2SessionResult session = wxMaService.getUserService().getSessionInfo(jsCode);
            openid = session == null ? null : session.getOpenid();
        } catch (WxErrorException e) {
            // 微信侧的失败（appid/secret 没配、js_code 过期、网络不通）如实回，不伪造成登录成功
            log.warn("code2Session 失败：{}", e.getMessage());
            BaseResult failed = BaseResult.setResult(ResultCodeEnum.FAILURE, null);
            failed.setMessage("微信登录失败：" + e.getMessage());
            return failed;
        }
        if (openid == null || openid.isEmpty()) {
            BaseResult failed = BaseResult.setResult(ResultCodeEnum.FAILURE, null);
            failed.setMessage("微信登录失败：未取到 openid");
            return failed;
        }

        User user = userService.findOrCreateByOpenid(openid);
        // token 是占位串，值就等于 openid（不签 JWT）；将来换真 JWT 时前端零改动
        LoginDto dto = new LoginDto(user.getOpenid(), user.getOpenid(), user.getLevel());
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
    }
}
