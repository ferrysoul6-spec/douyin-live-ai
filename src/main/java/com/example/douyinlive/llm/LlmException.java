package com.example.douyinlive.llm;

/** LLM 调用异常，业务侧捕获后走降级（返回空或 FAQ 兜底），不能让它打断直播流。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
