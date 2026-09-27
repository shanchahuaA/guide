package org.example.guide.controller;

import org.example.guide.pojo.User;
import org.example.guide.service.ITeachService;
import org.example.guide.service.IQuizService;
import org.example.guide.service.IUserService;
import org.example.guide.service.TeachGate;
import org.example.guide.service.UserLevels;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 越级门禁的**接线**断言（#43）。票面 AC 要的是"拦在路由层，不是靠提示词" ——
 * 矩阵本身由 {@link TeachGateTest} 逐个钉，这里钉的是另一半：
 * **拦下之后根本不会调服务**（也就不会查缓存、不会调大模型）。
 *
 * <p>纯 Mockito，不起 Spring（控制器字段虽是 {@code @Autowired}，Mockito 直接注入私有字段）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeachControllerTest {

    private static final String TOKEN = "oTest";

    @Mock
    private IUserService userService;
    @Mock
    private ITeachService teachService;
    @Mock
    private IQuizService quizService;

    @InjectMocks
    private TeachController controller;

    @BeforeEach
    void setUp() {
        givenUser(UserLevels.NOVICE);
    }

    @Test
    void 菜鸟问路线类被内容门禁拦下且不调服务() {
        BaseResult result = controller.ask(TOKEN, body("今日最佳路线"));

        // 内容门禁的响应码从 -100 改成 403（取 Shiro 那套语义），文案仍是按等级分支的那句
        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo(TeachGate.message(UserLevels.NOVICE));
        // 票面 AC：越级时不调大模型 —— 服务那一层压根没被碰过
        verifyNoInteractions(teachService);
    }

    @Test
    void 图鉴内问题照常交给服务() {
        when(teachService.ask(anyString(), anyString(), any())).thenReturn(ok());

        controller.ask(TOKEN, body("蘑菇有什么用"));

        verify(teachService).ask(eq(TOKEN), eq("蘑菇有什么用"), eq(UserLevels.NOVICE));
    }

    @Test
    void 高手问路线类不被拦() {
        givenUser(UserLevels.EXPERT);
        when(teachService.ask(anyString(), anyString(), any())).thenReturn(ok());

        controller.ask(TOKEN, body("今日最佳路线"));

        // 高手没有禁问，请求照常进服务（服务答得对不对由它自己的测试管）
        verify(teachService).ask(eq(TOKEN), anyString(), eq(UserLevels.EXPERT));
    }

    /** 控制器取的是**身份**上的等级，请求体里塞什么都不该改变门禁判定（契约 §7.0 防越权） */
    @Test
    void 请求体里的等级参数不影响门禁判定() {
        BaseResult result = controller.ask(TOKEN, Map.of("question", "今日最佳路线", "level", "2"));

        assertThat(result.getCode()).isEqualTo(403);
        verifyNoInteractions(teachService);
    }

    // ── 分级入口的委派（§7.7）────────────────────────────────────────────────
    //
    // ⚠️ 等级门禁在 **Shiro 路径层**，不在控制器里 —— 所以这里只验"委派对不对"；
    // "菜鸟调 beginner/route 被 403"那半条归过滤器，冒烟脚本覆盖。

    @Test
    void 分级入口route把请求交给服务() {
        when(teachService.route()).thenReturn(ok());

        controller.route(TOKEN);

        verify(teachService).route();
    }

    @Test
    void 分级入口speedrun把问题带身份等级交给服务() {
        givenUser(UserLevels.EXPERT);
        when(teachService.ask(anyString(), anyString(), any())).thenReturn(ok());

        controller.speedrun(TOKEN, body("怎么速通"));

        verify(teachService).ask(eq(TOKEN), eq("怎么速通"), eq(UserLevels.EXPERT));
    }

    // ── 个人页：昵称 / 头像（§7.1 / §7.8）──────────────────────────────────

    @Test
    void profile把昵称与头像一并带出() {
        User user = new User();
        user.setOpenid(TOKEN);
        user.setLevel(UserLevels.NOVICE);
        user.setNickname("小明");
        user.setAvatar("/avatars/oTest.png");
        when(userService.findByOpenid(TOKEN)).thenReturn(user);

        BaseResult result = controller.profile(TOKEN);

        assertThat(result.getData().get("nickname")).isEqualTo("小明");
        assertThat(result.getData().get("avatar")).isEqualTo("/avatars/oTest.png");
    }

    @Test
    void 保存昵称时头像那列不被清掉() {
        controller.updateProfile(TOKEN, Map.of("nickname", "小明"));

        // 只传昵称 → 头像传 null，服务层会跳过它（只写非空的那列）
        verify(userService).updateProfile(TOKEN, "小明", null);
    }

    @Test
    void 昵称与头像都空回参数错误且不写库() {
        BaseResult result = controller.updateProfile(TOKEN, Map.of("nickname", "  ", "avatar", ""));

        assertThat(result.getCode()).isEqualTo(-200);
        verify(userService, never()).updateProfile(anyString(), any(), any());
    }

    @Test
    void 头像上传缺文件回参数错误不是500() {
        BaseResult result = controller.uploadAvatar(TOKEN, null);

        assertThat(result.getCode()).isEqualTo(-200);
    }

    // ── 练习重置 / 跳级（§7.3 / §7.6）──────────────────────────────────────

    @Test
    void 重置练习把清进度交给服务() {
        controller.quizReset(TOKEN);

        verify(quizService).reset(TOKEN);
    }

    @Test
    void 跳级换档时顺带清空连对进度() {
        controller.devLevel(TOKEN);

        verify(userService).updateLevel(TOKEN, UserLevels.BEGINNER);
        // 题库按等级，换档后旧排除集里的题号不再成立，必须一并清
        verify(quizService).reset(TOKEN);
    }

    private void givenUser(int level) {
        User user = new User();
        user.setOpenid(TOKEN);
        user.setLevel(level);
        user.setApiKey("sk-x");
        when(userService.findByOpenid(TOKEN)).thenReturn(user);
    }

    private static Map<String, String> body(String question) {
        return Map.of("question", question);
    }

    private static BaseResult ok() {
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, Map.of("answer", "x"));
    }
}
