package org.example.guide.ai;

import lombok.Getter;

/**
 * 大模型调用失败。
 *
 * <p>{@code code} / {@code message} 就是**要放进统一响应包的那对值**（契约 §7.5 全是
 * {@code -100} + 中文提示），所以在抛出点就装配好，控制器不必再翻译一次 ——
 * 否则每个 catch 都要重写一遍"哪句话对应哪个错"。
 *
 * <p><b>不用 {@code utils/MyException}</b>：那个被 {@code ExceptionAutoUtil} 映射成
 * {@code -200 参数错误}，而这里的失败不是入参问题。让它在 {@code TeachServiceImpl} 里
 * 就地被 catch 掉，不依赖全局异常处理器。
 */
@Getter
public class AiException extends RuntimeException {

    private final int code;

    public AiException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
