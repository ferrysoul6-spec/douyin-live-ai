package com.example.douyinlive.llm;

import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;

/**
 * 语音合成路由器：主引擎 + 免费兜底。
 *
 * <p>默认策略：
 * <ul>
 *   <li>provider=seedtts（推荐）：火山 SeedTTS 2.0 主用，失败/超时/未配置 Key 时自动回落到本机 edge-tts（微软免费）</li>
 *   <li>provider=edge：只用本机 edge-tts</li>
 * </ul>
 *
 * <p>回落只在「主引擎返回空」时发生——即 Key 无效、配额用尽、网络异常、超时。
 * 缓存键两个引擎共用同一 audio-cache 目录，命中即秒回。
 */
@Component
@Primary
public class FallbackTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackTtsClient.class);

    private final AppProperties props;
    private final SeedTtsClient seedTts;
    private final EdgeTtsClient edgeTts;

    public FallbackTtsClient(AppProperties props, SeedTtsClient seedTts, EdgeTtsClient edgeTts) {
        this.props = props;
        this.seedTts = seedTts;
        this.edgeTts = edgeTts;
        boolean primarySeed = isSeedPrimary();
        log.info("TTS 路由：主 = {}，兜底 = {}（fallback-enabled={}）",
                primarySeed ? "SeedTTS 2.0" : "edge-tts（微软免费）",
                primarySeed ? "edge-tts（微软免费）" : "无",
                props.getTts().isFallbackToEdge());
    }

    private boolean isSeedPrimary() {
        return "seedtts".equalsIgnoreCase(props.getTts().getProvider());
    }

    @Override
    public boolean available() {
        return primary().available() || fallback().map(TtsClient::available).orElse(false);
    }

    @Override
    public Optional<String> cached(String text) {
        Optional<String> hit = primary().cached(text);
        if (hit.isPresent()) {
            return hit;
        }
        return fallback().flatMap(c -> c.cached(text));
    }

    @Override
    public Path cacheDir() {
        // 两个引擎写入同一目录，这里取主引擎的即可
        return isSeedPrimary() ? seedTts.cacheDir() : edgeTts.cacheDir();
    }

    @Override
    public Optional<String> synthesize(String text) {
        if (!props.getTts().isEnabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }

        TtsClient primary = primary();
        Optional<String> result = safeSynthesize(primary, text);
        if (result.isPresent()) {
            return result;
        }

        Optional<TtsClient> fb = fallback();
        if (fb.isEmpty()) {
            return Optional.empty();
        }
        if (!fb.get().available()) {
            log.debug("兜底引擎不可用（未安装 uvx？），语音降级为纯文本");
            return Optional.empty();
        }
        log.info("主引擎合成失败，回落到兜底引擎（edge-tts 免费）");
        return safeSynthesize(fb.get(), text);
    }

    private TtsClient primary() {
        return isSeedPrimary() ? seedTts : edgeTts;
    }

    /** 兜底引擎：仅当主引擎是 SeedTTS 且开启回落时才存在。 */
    private Optional<TtsClient> fallback() {
        if (!isSeedPrimary() || !props.getTts().isFallbackToEdge()) {
            return Optional.empty();
        }
        return Optional.of(edgeTts);
    }

    /** 任何异常都不向上抛：语音是增强项，失败就降级纯文本。 */
    private Optional<String> safeSynthesize(TtsClient client, String text) {
        try {
            if (!client.available()) {
                return Optional.empty();
            }
            return client.synthesize(text);
        } catch (RuntimeException e) {
            log.warn("语音合成异常（{}）: {}", client.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }
}
