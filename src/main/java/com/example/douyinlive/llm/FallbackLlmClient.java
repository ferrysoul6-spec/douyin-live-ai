package com.example.douyinlive.llm;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;

/**
 * LLM 路由器：主模型 + 一条兜底链。
 *
 * <p>主模型失败（限流 / 超时 / 模型不可用 / 返回空）后按配置顺序逐个尝试，任一成功即返回。
 * 链上所有模型共用同一 API Key 与 base-url，换的只是 model 字段。
 *
 * <p>两道刹车：<br>
 * 1. 账号级故障（401 Key 无效 / 403 欠费或无权限）立刻放弃整条链——换模型解决不了，
 *    不刹车的话十几个模型会被挨个撞一遍、还刷满日志；<br>
 * 2. 整条链有总预算（fallback-budget-seconds），超了停手，避免一条弹幕排队等十几秒。
 *
 * <p>全链失败后仍由 ReplyService 降级到预设话术池，不影响直播。
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
        List<String> chain = chain();
        log.info("LLM 调用链（{} 个，fallback-enabled={}）：{}",
                chain.size(), props.getLlm().isFallbackEnabled(), chain);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        AppProperties.Llm cfg = props.getLlm();
        List<String> chain = chain();
        long deadline = System.currentTimeMillis() + Math.max(1, cfg.getFallbackBudgetSeconds()) * 1000L;
        LlmException last = null;

        for (int i = 0; i < chain.size(); i++) {
            String model = chain.get(i);
            // 主模型等满超时，兜底模型用短的：单条弹幕不能被一个模型拖住
            int timeout = i == 0 ? cfg.getTimeoutSeconds() : cfg.getFallbackTimeoutSeconds();
            try {
                String reply = client.chatWithModel(model, timeout, systemPrompt, userPrompt);
                if (i > 0) {
                    log.info("兜底第 {} 个模型 {} 成功", i, model);
                }
                return reply;
            } catch (LlmException e) {
                // 两次调用如果抛的是同一个异常实例，addSuppressed 会抛 IllegalArgumentException，
                // 把 LlmException 变成别的异常，绕过业务层的 catch (LlmException)
                if (last != null && e != last) {
                    e.addSuppressed(last);
                }
                last = e;
                if (e.isFatal()) {
                    log.warn("模型 {} 账号级失败（{}），放弃整条兜底链", model, e.getMessage());
                    throw e;
                }
                if (System.currentTimeMillis() >= deadline) {
                    log.warn("兜底链超出 {}s 预算，停在 {}: {}", cfg.getFallbackBudgetSeconds(),
                            model, e.getMessage());
                    break;
                }
                if (i + 1 < chain.size()) {
                    log.warn("模型 {} 失败，换下一个 {}: {}", model, chain.get(i + 1), e.getMessage());
                }
            }
        }
        if (last == null) {
            throw new LlmException("没有可用的模型（live-ai.llm.model 为空）");
        }
        throw last;
    }

    /** 实际调用链：主模型 + 兜底链（旧的单个 fallback-model 也并进链里）。 */
    private List<String> chain() {
        AppProperties.Llm cfg = props.getLlm();
        List<String> out = new ArrayList<>();
        addModel(out, cfg.getModel());
        if (cfg.isFallbackEnabled()) {
            List<String> list = cfg.getFallbackModels();
            if (list != null) {
                for (String m : list) {
                    addModel(out, m);
                }
            }
            addModel(out, cfg.getFallbackModel());
        }
        return out;
    }

    /** 去重（不分大小写）+ 忽略空白：同名模型调两次纯属浪费。 */
    private void addModel(List<String> out, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        String m = model.trim();
        for (String e : out) {
            if (e.equalsIgnoreCase(m)) {
                return;
            }
        }
        out.add(m);
    }
}
