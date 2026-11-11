package com.example.douyinlive.llm;

/** LLM 调用异常，业务侧捕获后走降级（返回空或 FAQ 兜底），不能让它打断直播流。 */
public class LlmException extends RuntimeException {

    /** HTTP 状态码；0 表示不是 HTTP 层面的失败（超时、序列化、中断）。 */
    private final int status;

    public LlmException(String message) {
        this(message, 0, null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public LlmException(String message, int status) {
        this(message, status, null);
    }

    public LlmException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /**
     * 账号级故障：Key 无效（401）或账号欠费/无权限（403）。
     *
     * <p>这类错误换哪个模型都一樣，兜底链要立刻放弃，否则十几个模型会被挨个撞一遍。
     */
    public boolean isFatal() {
        return status == 401 || status == 403;
    }
}
