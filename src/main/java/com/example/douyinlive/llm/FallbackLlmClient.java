package com.example.douyinlive.llm;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;

/**
 * LLM 路由器：主模型 + 备用模型。
 *
 * <p>主模型失败（限流 / 超时 / 模型不可用 / 返回空）时，用备用模型重试一次。
 * 两者共用同一 API Key 与 base-url，换的只是 model 字段。
 *
 * <p>边界：只能兜「模型级」故障。Key 失效、账号欠费、方舟整体故障时备用模型同样失败，
 * 最终仍由 ReplyService 降级到预设话术池。
 */
@Component
@Primary
public class FallbackLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmClient.class);

    private final AppProperties props;
    private final ArkLlmClient client;

    public FallbackLlmClient(AppProperties props, ArkLlmClient client) {
        this.props = props;
        this.client = client;
        AppProperties.Llm cfg = props.getLlm();
        log.info("LLM 路由：主 = {}，兜底 = {}（fallback-enabled={}）",
                cfg.getModel(), fallbackModel().orElse("无"), cfg.isFallbackEnabled());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        AppProperties.Llm cfg = props.getLlm();
        try {
            return client.chatWithModel(cfg.getModel(), cfg.getTimeoutSeconds(), systemPrompt, userPrompt);
        } catch (LlmException primaryFail) {
            Optional<String> fallback = fallbackModel();
            if (fallback.isEmpty()) {
                throw primaryFail;
            }
            log.warn("主模型 {} 失败，回落到 {}: {}", cfg.getModel(), fallback.get(), primaryFail.getMessage());
            try {
                return client.chatWithModel(fallback.get(), cfg.getFallbackTimeoutSeconds(),
                        systemPrompt, userPrompt);
            } catch (LlmException fallbackFail) {
                // 两次调用如果抛的是同一个异常实例，addSuppressed 会抛 IllegalArgumentException，
                // 把 LlmException 变成别的异常，绕过业务层的 catch (LlmException)
                if (fallbackFail != primaryFail) {
                    fallbackFail.addSuppressed(primaryFail);
                }
                throw fallbackFail;
            }
        }
    }

    /** 备用模型：未开启 / 未配置 / 与主模型同名时视为不存在，避免把同一个模型调两次。 */
    private Optional<String> fallbackModel() {
        AppProperties.Llm cfg = props.getLlm();
        String model = cfg.getFallbackModel();
        if (!cfg.isFallbackEnabled() || model == null || model.isBlank()) {
            return Optional.empty();
        }
        return model.equalsIgnoreCase(cfg.getModel()) ? Optional.empty() : Optional.of(model);
    }
}
