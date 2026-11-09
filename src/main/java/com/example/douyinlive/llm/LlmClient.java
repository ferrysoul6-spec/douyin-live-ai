package com.example.douyinlive.llm;

/**
 * LLM 客户端抽象。后续换模型只需新增实现类，业务代码不用改。
 */
public interface LlmClient {

    /**
     * 单轮生成。
     *
     * @param systemPrompt 系统提示词（人设 + 规则）
     * @param userPrompt   用户输入（观众评论 + FAQ 上下文）
     * @return 生成文本；失败时抛 LlmException，由调用方决定降级策略
     */
    String chat(String systemPrompt, String userPrompt);
}
