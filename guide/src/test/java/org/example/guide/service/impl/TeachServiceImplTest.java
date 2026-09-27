package org.example.guide.service.impl;

import org.example.guide.ai.AiException;
import org.example.guide.ai.DeepSeekClient;
import org.example.guide.cache.AnswerCache;
import org.example.guide.cache.ItemCache;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.User;
import org.example.guide.service.IUserService;
import org.example.guide.service.TeachGate;
import org.example.guide.service.UserLevels;
import org.example.guide.utils.BaseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TeachServiceImpl} 的编排分支。**不加载 Spring、不连 Redis / MySQL、不真调 DeepSeek** ——
 * 这里钉的是"哪条路会调大模型、哪条不会"，以及每条路回给小程序的是什么。
 *
 * <p>缓存放最前面这件事值得单独钉：命中缓存时不碰 Key、不碰大模型。若顺序反了，
 * "同一个问题问第二次不再调大模型"这条 AC 会**在用户清掉 Key 之后失效** —— 而那种失败
 * 只在演示当天才会显形。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeachServiceImplTest {

    private static final String OPENID = "oTest";
    private static final String QUESTION = "蘑菇有什么用";

    @Mock
    private IUserService userService;
    @Mock
    private ItemCache itemCache;
    @Mock
    private AnswerCache answerCache;
    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private BilibiliRouteService routeService;

    @BeforeEach
    void setUp() {
        when(itemCache.getAll()).thenReturn(List.of(mushroom()));
        when(answerCache.get(QUESTION)).thenReturn(null);
        when(deepSeekClient.chat(anyString(), anyString(), anyString())).thenReturn("模型给的答案");
    }

    private TeachServiceImpl service(boolean enabled) {
        return new TeachServiceImpl(userService, itemCache, answerCache, deepSeekClient, routeService, enabled);
    }

    private void givenUserWithKey(String apiKey) {
        User user = new User();
        user.setOpenid(OPENID);
        user.setApiKey(apiKey);
        when(userService.findByOpenid(OPENID)).thenReturn(user);
    }

    // ── 缓存 ────────────────────────────────────────────────────────────────

    @Test
    void 缓存命中时不调大模型也不看Key() {
        when(answerCache.get(QUESTION)).thenReturn("缓存里的答案");

        BaseResult result = service(true).ask(OPENID, QUESTION, UserLevels.NOVICE);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("answer")).isEqualTo("缓存里的答案");
        // 票面 AC：同一问题问第二次不再调大模型
        verifyNoInteractions(deepSeekClient);
        // 也不该去看用户行 —— 答案与 Key 无关，用户清掉 Key 也不该让缓存过的回答失效
        verifyNoInteractions(userService);
    }

    @Test
    void 未命中时调大模型并把回答回填缓存() {
        givenUserWithKey("sk-good");

        BaseResult result = service(true).ask(OPENID, QUESTION, UserLevels.NOVICE);

        assertThat(result.getData().get("answer")).isEqualTo("模型给的答案");
        // 回填是"第二次不再调大模型"的唯一保证，漏了它缓存就永远只是穿透
        verify(answerCache).put(QUESTION, "模型给的答案");
    }

    @Test
    void 上下文用的是图鉴缓存而不是直查库() {
        givenUserWithKey("sk-good");

        service(true).ask(OPENID, QUESTION, UserLevels.NOVICE);

        verify(itemCache).getAll();
        // 上下文里要带图鉴条目本身：问"蘑菇有什么用"，模型必须看得见蘑菇的数值
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).chat(eq("sk-good"), anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("喇叭菇").contains(QUESTION);
    }

    @Test
    void 等级边界注入系统提示词软约束() {
        givenUserWithKey("sk-good");

        service(true).ask(OPENID, QUESTION, UserLevels.EXPERT);

        // 契约 §7.0 的"双保险"：硬拦在路由层，系统提示词里再注入一次等级与边界
        var systemPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).chat(eq("sk-good"), systemPrompt.capture(), anyString());
        assertThat(systemPrompt.getValue()).contains(TeachGate.boundaryFor(UserLevels.EXPERT));
    }

    // ── 路线类（#44）─────────────────────────────────────────────────────────

    @Test
    void 路线类问题回后端构造的链接且不调大模型() {
        when(routeService.links()).thenReturn(List.of(
                Map.of("title", "攻略", "url", "https://www.bilibili.com/video/BV1xx")));

        BaseResult result = service(true).ask(OPENID, "今日最佳路线", UserLevels.EXPERT);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("answer")).isEqualTo(TeachServiceImpl.ROUTE_ANSWER);
        assertThat((List<?>) result.getData().get("links")).hasSize(1);
        // 链接由后端构造，模型不能联网（CONTEXT.md「每日路线」），也就不需要用户的 Key
        verifyNoInteractions(deepSeekClient);
        verifyNoInteractions(userService);
    }

    @Test
    void 路线类问题不进回答缓存() {
        when(routeService.links()).thenReturn(List.of(
                Map.of("title", "攻略", "url", "https://www.bilibili.com/video/BV1xx")));

        service(true).ask(OPENID, "今日最佳路线", UserLevels.EXPERT);

        // 链接里带当天日期，缓存到明天就是过期链接，所以路线回答一律不落缓存
        verify(answerCache, never()).put(anyString(), anyString());
    }

    // ── 降级 ────────────────────────────────────────────────────────────────

    @Test
    void ai未启用时返回预置文案而不是报错() {
        BaseResult result = service(false).ask(OPENID, QUESTION, UserLevels.NOVICE);

        // 票面 AC：enabled=false 时仍返回文案（不是 -100、更不是 500）
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("answer")).isEqualTo(TeachServiceImpl.DISABLED_ANSWER);
        verifyNoInteractions(deepSeekClient);
        verifyNoInteractions(userService);
    }

    // ── 失败路径：一律 -100，且键面不变 ──────────────────────────────────────

    @Test
    void 没填Key时提示先填且不调大模型() {
        givenUserWithKey(null);

        BaseResult result = service(true).ask(OPENID, QUESTION, UserLevels.NOVICE);

        assertThat(result.getCode()).isEqualTo(-100);
        assertThat(result.getMessage()).isEqualTo("请先填写你的 DeepSeek API Key");
        verifyNoInteractions(deepSeekClient);
    }

    @Test
    void Key校验失败时回客户端的错误码与文案不是500() {
        givenUserWithKey("sk-wrong");
        when(deepSeekClient.chat(anyString(), anyString(), anyString()))
                .thenThrow(new AiException(-100, "API Key 无效", null));

        BaseResult result = service(true).ask(OPENID, QUESTION, UserLevels.NOVICE);

        // 票面 AC：故意填错 Key → code = -100 + 「API Key 无效」，不是 500
        assertThat(result.getCode()).isEqualTo(-100);
        assertThat(result.getMessage()).isEqualTo("API Key 无效");
        // 失败的回答不进缓存，否则一次手滑的错误 Key 会把这个问题永久钉死
        verify(answerCache, never()).put(anyString(), anyString());
    }

    @Test
    void 问题为空时回参数提示且不调大模型() {
        BaseResult result = service(true).ask(OPENID, "  ", UserLevels.NOVICE);

        assertThat(result.getCode()).isEqualTo(-100);
        verifyNoInteractions(deepSeekClient);
    }

    @Test
    void 图鉴数据取不到时不拿空上下文去问模型() {
        givenUserWithKey("sk-good");
        when(itemCache.getAll()).thenReturn(List.of());

        BaseResult result = service(true).ask(OPENID, QUESTION, UserLevels.NOVICE);

        // 空上下文下模型只会编一段话出来 —— 那种回答比"取不到"更糟
        assertThat(result.getCode()).isEqualTo(-100);
        verifyNoInteractions(deepSeekClient);
        verify(answerCache, never()).put(anyString(), anyString());
    }

    @Test
    void 用户行取不到时当未登录() {
        when(userService.findByOpenid(OPENID)).thenReturn(null);

        assertThat(service(true).ask(OPENID, QUESTION, UserLevels.NOVICE).getCode()).isEqualTo(-100);
    }

    @Test
    void 每次都只调一次大模型() {
        givenUserWithKey("sk-good");

        service(true).ask(OPENID, QUESTION, UserLevels.NOVICE);

        verify(deepSeekClient, times(1)).chat(anyString(), anyString(), anyString());
    }

    private static Item mushroom() {
        Item item = new Item();
        item.setId(1L);
        item.setNameEn("Bugle Shroom");
        item.setNameZh("喇叭菇");
        item.setWeight(5.0f);
        return item;
    }
}
