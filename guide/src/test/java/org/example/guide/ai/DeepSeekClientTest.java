package org.example.guide.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeepSeekClient} 的响应解析与错误码映射。
 *
 * <p>**不真调 DeepSeek**（票面 AC 明写"用假的 HTTP 响应"）：被测的就是
 * {@code readContent} 这一个纯函数 —— 给它一段响应文本，看它取出什么、抛什么。
 * 真调那一条路只有用户本人的 Key 能走，见报告里的「未验证」。
 *
 * <p>两件事在这里钉死，它们都是**会静默错**的那类：
 * <ul>
 *   <li>{@code reasoning_content} 不能当答案 —— 思考模式默认开着，两个字段同时存在，
 *       读错了会把模型的草稿纸回给小程序；</li>
 *   <li>DeepSeek 的错误码要变成 {@code -100} + 中文提示，不是 500（票面 AC）。</li>
 * </ul>
 */
class DeepSeekClientTest {

    /**
     * 直接调包级可见的解析方法。这个 {@code RestClient.Builder} 只是构造函数的入参、
     * 一次请求都不会发出去 —— 解析与"怎么发请求"无关。
     */
    private final DeepSeekClient client = new DeepSeekClient(
            "https://api.deepseek.com", "deepseek-flash",
            RestClient.builder(), JsonMapper.builder().build());

    // ── 只读 content ───────────────────────────────────────────────────────

    @Test
    void 思考模式的响应只取content不取reasoning_content() {
        // 真实响应里两个字段并存（deepseek-reasoner 一类模型默认开启思考）。
        // reasoning_content 是思考过程：可能英文、可能半途改主意、可能复述系统提示词
        String raw = """
                {
                  "id": "chat-1",
                  "choices": [{
                    "index": 0,
                    "finish_reason": "stop",
                    "message": {
                      "role": "assistant",
                      "reasoning_content": "Let me think. The user asks about mushrooms... I should check the compendium data first.",
                      "content": "蘑菇在 PEAK 里主要是当食物吃，能顶一阵饥饿。"
                    }
                  }]
                }
                """;

        String answer = client.readContent(ok(raw));

        assertThat(answer).isEqualTo("蘑菇在 PEAK 里主要是当食物吃，能顶一阵饥饿。");
        assertThat(answer).doesNotContain("Let me think");
    }

    @Test
    void 没有思考字段的普通响应照样取content() {
        String raw = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Hot Dog 生吃回复 30 点饥饿。"}}]}
                """;

        assertThat(client.readContent(ok(raw))).isEqualTo("Hot Dog 生吃回复 30 点饥饿。");
    }

    @Test
    void content为空时当失败而不是回一句空答案() {
        String raw = """
                {"choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"只想了没答"}}]}
                """;

        assertThatThrownBy(() -> client.readContent(ok(raw)))
                .isInstanceOf(AiException.class)
                .extracting(e -> ((AiException) e).getCode())
                .isEqualTo(-100);
    }

    @Test
    void 响应不是JSON时当失败而不是抛原始异常() {
        assertThatThrownBy(() -> client.readContent(ok("<html>502 Bad Gateway</html>")))
                .isInstanceOf(AiException.class);
    }

    // ── 错误码映射（契约 §7.5）───────────────────────────────────────────────

    @Test
    void 无效Key的401空响应体映射成APIKey无效() {
        // ⚠️ 实测行为：DeepSeek 对无效 Key 回的是 **401 且响应体为空**（不是带 error 节点的 JSON）。
        // 因此错误映射必须**先看状态码** —— 只认响应体 error.code 的话，这一条会被报成
        // "AI 返回的不是合法 JSON"，而票面 AC 要的正是「API Key 无效」。这条断言就是那次回归的保护
        AiException e = catchThrowableOfType(
                () -> client.readContent(new DeepSeekClient.RawResponse(401, null)), AiException.class);

        assertThat(e.getCode()).isEqualTo(-100);
        assertThat(e.getMessage()).isEqualTo("API Key 无效");
    }

    @Test
    void 状态码无效时也照样映射() {
        // 状态码是可靠信号，体里有码只是补充
        assertThat(client.toFailure("401", "").getMessage()).isEqualTo("API Key 无效");
    }

    @Test
    void 余额不足的402带错误体() {
        String raw = """
                {"error":{"message":"Insufficient Balance","code":"402"}}
                """;

        AiException e = catchThrowableOfType(
                () -> client.readContent(new DeepSeekClient.RawResponse(402, raw)), AiException.class);

        assertThat(e.getMessage()).isEqualTo("API Key 余额不足");
    }

    @Test
    void 限流与过载都映射成服务繁忙() {
        assertThat(client.toFailure("429", "").getMessage()).isEqualTo("AI 服务繁忙，请稍后再试");
        assertThat(client.toFailure("503", "").getMessage()).isEqualTo("AI 服务繁忙，请稍后再试");
    }

    @Test
    void 其余错误映射成服务异常并带上原文() {
        AiException e = client.toFailure("500", "Internal Server Error");

        assertThat(e.getCode()).isEqualTo(-100);
        assertThat(e.getMessage()).isEqualTo("AI 服务异常，请重试：Internal Server Error");
    }

    @Test
    void 认不出的状态码也归到服务异常不是500() {
        AiException e = client.toFailure("", "");

        // 票面 AC：任何一种失败都不能是 500 —— 小程序靠响应包里的 code 判成败
        assertThat(e.getCode()).isEqualTo(-100);
        assertThat(e.getMessage()).isEqualTo("AI 服务异常，请重试");
    }

    @Test
    void 非JSON的错误体不会反过来报成解析失败() {
        // 500 时上游可能是网关吐的 HTML。那种体的内容不该盖掉"这是 AI 服务异常"这个判断
        AiException e = catchThrowableOfType(
                () -> client.readContent(ok500("<html>502 Bad Gateway</html>")), AiException.class);

        assertThat(e.getMessage()).startsWith("AI 服务异常，请重试");
    }

    /** 正常 200 的响应包一层状态码 —— 被测方法收的是"状态码 + 体"这一对 */
    private static DeepSeekClient.RawResponse ok(String body) {
        return new DeepSeekClient.RawResponse(200, body);
    }

    private static DeepSeekClient.RawResponse ok500(String body) {
        return new DeepSeekClient.RawResponse(500, body);
    }

    private static <T extends Throwable> T catchThrowableOfType(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                                                Class<T> type) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(callable, type);
    }
}
