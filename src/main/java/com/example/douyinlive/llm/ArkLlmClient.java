package com.example.douyinlive.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 火山方舟大模型客户端（Chat Completions / OpenAI 兼容协议）。
 *
 * <p>API Key 从配置读取（环境变量 ARK_API_KEY，兼容 DEEPSEEK_API_KEY），
 * 严禁写进代码库或提交到 Git。换其他 OpenAI 兼容服务只改 base-url + model。
 */
@Component
public class ArkLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ArkLlmClient.class);

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ArkLlmClient(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        AppProperties.Llm cfg = props.getLlm();
        return chatWithModel(cfg.getModel(), cfg.getTimeoutSeconds(), systemPrompt, userPrompt);
    }

    /**
     * 用指定模型名与超时调用一次，供 FallbackLlmClient 在同一条链路上换模型重试。
     * 除 model 与超时外，Key / base-url / temperature / thinking 全部沿用配置。
     */
    public String chatWithModel(String model, int timeoutSeconds, String systemPrompt, String userPrompt) {
        AppProperties.Llm cfg = props.getLlm();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new LlmException("未配置 ARK_API_KEY（兼容 DEEPSEEK_API_KEY），无法调用大模型");
        }

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", model);
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        // 火山方舟 doubao 系默认开深度思考，直播场景延迟不可接受，按配置显式关闭
        if (cfg.isDisableThinking()) {
            body.put("thinking", Map.of("type", "disabled"));
        }

        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmException("请求体序列化失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("方舟返回非 200: model={}, status={}, body={}", model, resp.statusCode(), abbreviate(resp.body()));
                throw new LlmException("方舟 HTTP " + resp.statusCode());
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new LlmException("方舟响应缺少 choices[0].message.content");
            }
            return content.asText().trim();
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("方舟调用被中断", e);
        } catch (Exception e) {
            throw new LlmException("方舟调用失败: " + e.getMessage(), e);
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
