package org.example.guide.ai;

import org.example.guide.utils.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 客户端：全后端**唯一碰大模型 HTTP** 的地方（契约 §7 开头、§9.2）。
 *
 * <p>不引 SDK —— DeepSeek 是 OpenAI 兼容协议，一次 {@code POST /chat/completions} 而已，
 * 引一个 SDK 换来的只是"多一份要跟着升级的依赖"。
 *
 * <p><b>只取 {@code choices[0].message.content}</b>，绝不碰 {@code reasoning_content}：
 * DeepSeek 的思考模式默认开着，响应里两个字段同时存在，而 {@code reasoning_content}
 * 是模型的思考过程、不是答案（可能是英文、可能半途改主意、可能直接泄漏系统提示词）。
 * 一并读进去就是把"草稿纸"当答案回给小程序。
 *
 * <p>配置项与既有的 {@code guide.avatar-*} 同一命名习惯：{@code guide.ai.base-url} / {@code .model}。
 * {@code enabled} 不在这里读 —— 那是路由层的开关（本类只负责"真调一次"这个动作），
 * 关掉时根本没有调用方，见 {@code TeachServiceImpl.ask}。
 */
@Component
public class DeepSeekClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /**
     * 不上送 {@code max_tokens}：那就是给答案长度设一个闸，图鉴问答的答案长度本来就有长有短。
     *
     * <p>也不上送 {@code stream}：本类只处理整包响应，流式是另一个功能（要配 SSE 的读法）。
     */
    private static final boolean STREAM = false;

    private final String baseUrl;
    private final String model;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DeepSeekClient(@Value("${guide.ai.base-url}") String baseUrl,
                          @Value("${guide.ai.model}") String model,
                          RestClient.Builder restClientBuilder,
                          ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /** 一次调用的结果：HTTP 状态码 + 响应体原文（失败时 body 可能为 null） */
    record RawResponse(int status, String body) {
    }

    /**
     * 真调一次对话补全。
     *
     * @param apiKey       用户自己的 Key（落 {@code user.api_key} 列），走 {@code Authorization: Bearer}
     * @param systemPrompt 系统提示词（分级约束、能力边界都注入在这里）
     * @param userPrompt   用户这一轮的问题与上下文
     * @return 模型的答案正文（{@code choices[0].message.content}）
     * @throws AiException 调用失败。**错误码映射只在这一处**，见 {@link #toFailure}
     */
    public String chat(String apiKey, String systemPrompt, String userPrompt) {
        return chat(apiKey, systemPrompt, userPrompt, false);
    }

    /**
     * 和 {@link #chat} 同一件事，只多上送 {@code response_format = {"type":"json_object"}}。
     *
     * <p>出题（#42）要的是一段**能解析成题库的 JSON**，自然语言答案没法用。开启 JSON 模式后
     * DeepSeek 只吐一个 JSON 对象（注意：它的 {@code json_object} 模式要求提示词里出现 "json" 字样，
     * 见 {@code QuizGenerator} 的系统提示词）。
     *
     * <p>解析与重试不在这里 —— 那是出题方的编排，本类只负责"用 JSON 模式调一次"。
     */
    public String chatJson(String apiKey, String systemPrompt, String userPrompt) {
        return chat(apiKey, systemPrompt, userPrompt, true);
    }

    private String chat(String apiKey, String systemPrompt, String userPrompt, boolean jsonMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", STREAM);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        RawResponse response = exchange(apiKey, body);
        return readContent(response);
    }

    /**
     * 发请求并原样拿回状态码与响应体。
     *
     * <p>用 {@code exchange} 而不是 {@code retrieve().body(...)}：后者拿不到状态码，
     * 而状态码是这里**唯一可靠的信号**（理由见下）。{@code exchange} 也不抛 4xx/5xx，
     * 错误响应的体因此能原样读回来，由 {@link #toFailure} 判。
     *
     * <p><b>实测踩到的坑：DeepSeek 对无效 Key 回的是 401 且响应体为空</b>
     * （不是带 {@code error} 节点的 JSON）。早先只从响应体里认 {@code error.code}，
     * 那种 401 于是被报成"AI 返回的不是合法 JSON"，而票面 AC 要的正是「API Key 无效」。
     * 所以判据以**状态码**为主，体里的 {@code error.code} 只作补充。
     */
    private RawResponse exchange(String apiKey, Map<String, Object> body) {
        try {
            var spec = restClient.post()
                    .uri(baseUrl + CHAT_COMPLETIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);

            // 响应体与状态码要一起拿到，所以用 exchange 而不是 retrieve：
            // retrieve().body(...) 拿不到状态码，而状态码是这里的唯一可靠信号
            return spec.exchange((request, response) ->
                    new RawResponse(response.getStatusCode().value(), readBody(response)));
        } catch (Exception e) {
            // 连不上 / 超时 / DNS 挂了 —— 不是"Key 无效"，但性质一样：这一轮问不出答案
            throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 服务连不上，请检查网络后重试", e);
        }
    }

    /** 读响应体。空体（DeepSeek 的 401 就是）返回 null，不在这里当错误 */
    private static String readBody(ClientHttpResponse response) {
        try (var in = response.getBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从一次响应里取出答案正文。
     *
     * <p>先判状态码（非 2xx 一律走 {@link #toFailure}），再取
     * {@code choices[0].message.content}。
     *
     * <p>包级可见是为了单测 —— 单测用**假的响应**直接钉住"只读 content"与错误码映射两条口径，
     * 不真调 DeepSeek（票面 AC 明写）。
     */
    String readContent(RawResponse response) {
        if (response.status() < 200 || response.status() >= 300) {
            String bodyErrorCode = "";
            String bodyMessage = "";
            if (response.body() != null) {
                JsonNode root;
                try {
                    root = objectMapper.readTree(response.body());
                } catch (Exception e) {
                    // 错误响应的体不是 JSON（甚至为空）时不该反过来当解析错误报出去
                    root = null;
                }
                if (root != null) {
                    JsonNode error = root.path("error");
                    if (!error.isMissingNode() && !error.isNull()) {
                        bodyErrorCode = error.path("code").asString("");
                        bodyMessage = error.path("message").asString("");
                    }
                }
            }
            // 状态码优先：体里没有 error 节点（401 就是空体）时只能靠它
            throw toFailure(String.valueOf(response.status()), bodyMessage.isEmpty() ? bodyErrorCode : bodyMessage);
        }

        if (response.body() == null || response.body().isBlank()) {
            throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 没有返回内容，请重试", null);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 返回的不是合法 JSON，请重试", e);
        }

        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asString("").isBlank()) {
            throw new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 没有返回答案，请重试", null);
        }
        return content.asString();
    }

    /**
     * DeepSeek 的错误 → 契约 §7.5 的中文提示，统一 {@code -100}。
     *
     * <p>映射依据是 HTTP 状态码；响应体里的 {@code error.code} 作为补充（有些错误只在体里给码）。
     * <ul>
     *   <li>{@code 401} → API Key 无效；{@code 402} → 余额不足；</li>
     *   <li>{@code 429} / {@code 503} → 服务繁忙（限流与过载，用户能做的一样是"等一会儿"）；</li>
     *   <li>其余（{@code 400} / {@code 422} / {@code 500} …）→ 服务异常。</li>
     * </ul>
     *
     * <p>**为什么不论哪种都是 {@code -100} 而不是 500**：调用方是小程序，它靠统一响应包里的
     * {@code code} 判成败（{@code utils/api.js}），500 只会让它走同一个 reject 分支、
     * 却把"AI 那边的问题"记成"我们这边崩了"。票面 AC 明写"不是 500"。
     */
    AiException toFailure(String codeOrStatus, String errorMessage) {
        String text = codeOrStatus == null ? "" : codeOrStatus;
        if (text.contains("401")) {
            return new AiException(ResultCodeEnum.FAILURE.getCode(), "API Key 无效", null);
        }
        if (text.contains("402")) {
            return new AiException(ResultCodeEnum.FAILURE.getCode(), "API Key 余额不足", null);
        }
        if (text.contains("429") || text.contains("503")) {
            return new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 服务繁忙，请稍后再试", null);
        }
        String suffix = (errorMessage == null || errorMessage.isBlank()) ? "" : "：" + errorMessage;
        return new AiException(ResultCodeEnum.FAILURE.getCode(), "AI 服务异常，请重试" + suffix, null);
    }
}
