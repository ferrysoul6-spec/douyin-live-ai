package com.example.douyinlive.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 火山 SeedTTS 2.0（豆包语音合成）实现。
 *
 * <p>接口：POST https://openspeech.bytedance.com/api/v3/tts/unidirectional
 * <ul>
 *   <li>X-Api-Key：语音专属 Key（UUID 形态，不是 ark- 开头的方舟 Key）</li>
 *   <li>X-Api-Resource-Id：seed-tts-2.0</li>
 *   <li>返回 chunked JSON，音频在每行的 data 字段（base64），需拼接</li>
 * </ul>
 *
 * <p>相比本机 edge-tts：不依赖 uvx/python（可用于抖音云容器），
 * 实测 3.4s 音频合成耗时约 0.9s，走国内节点延迟更低。
 * 缓存策略与 Edge 实现一致（speaker|text 哈希），两者可共用同一 audio-cache 目录。
 */
@Component
public class SeedTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(SeedTtsClient.class);
    private static final int SUCCESS_CODE = 20000000;

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Path cacheDir;

    public SeedTtsClient(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.cacheDir = Path.of(props.getTts().getDir()).toAbsolutePath().normalize();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        if (props.getTts().getSeedtts().getApiKey() == null
                || props.getTts().getSeedtts().getApiKey().isBlank()) {
            log.warn("SeedTTS 未配置语音 API Key（live-ai.tts.seedtts.api-key / SEEDTTS_API_KEY），"
                    + "语音合成将降级为纯文本");
        } else {
            log.info("SeedTTS 就绪，音色 {}，资源 {}",
                    props.getTts().getSeedtts().getSpeaker(), props.getTts().getSeedtts().getResourceId());
        }
    }

    @Override
    public boolean available() {
        return props.getTts().isEnabled()
                && props.getTts().getSeedtts().getApiKey() != null
                && !props.getTts().getSeedtts().getApiKey().isBlank();
    }

    @Override
    public Path cacheDir() {
        return cacheDir;
    }

    @Override
    public Optional<String> cached(String text) {
        if (!props.getTts().isEnabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String fileName = TtsCacheKey.of(props.getTts().getSeedtts().getSpeaker(), text) + ".mp3";
        java.io.File f = cacheDir.resolve(fileName).toFile();
        return (f.exists() && f.length() > 0) ? Optional.of(fileName) : Optional.empty();
    }

    @Override
    public Optional<String> synthesize(String text) {
        if (!available() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        AppProperties.Seedtts cfg = props.getTts().getSeedtts();
        String fileName = TtsCacheKey.of(cfg.getSpeaker(), text) + ".mp3";
        Path target = cacheDir.resolve(fileName);
        if (Files.exists(target)) {
            try {
                if (Files.size(target) > 0) {
                    log.debug("TTS 缓存命中: {}", fileName);
                    return Optional.of(fileName);
                }
            } catch (Exception ignored) {
                // 读取失败则重新合成
            }
        }

        byte[] audio = requestAudio(cfg, text);
        if (audio == null || audio.length == 0) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(cacheDir);
            Path tmp = cacheDir.resolve(fileName + ".tmp");
            Files.write(tmp, audio);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("SeedTTS 合成完成 {} ({} bytes)", fileName, audio.length);
            return Optional.of(fileName);
        } catch (Exception e) {
            log.warn("SeedTTS 音频落盘失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 调接口并拼接 base64 音频分片；任何异常都返回 null 由调用方降级。 */
    private byte[] requestAudio(AppProperties.Seedtts cfg, String text) {
        Map<String, Object> reqParams = new LinkedHashMap<>();
        reqParams.put("text", text);
        reqParams.put("speaker", cfg.getSpeaker());
        reqParams.put("audio_params", Map.of(
                "format", "mp3",
                "sample_rate", cfg.getSampleRate()));
        Map<String, Object> body = Map.of(
                "user", Map.of("uid", "live-ai"),
                "req_params", reqParams);

        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("SeedTTS 请求序列化失败: {}", e.getMessage());
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getEndpoint()))
                .timeout(Duration.ofSeconds(props.getTts().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", cfg.getApiKey())
                .header("X-Api-Resource-Id", cfg.getResourceId())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("SeedTTS HTTP {}: {}", resp.statusCode(), abbreviate(resp.body()));
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            boolean success = false;
            for (String line : resp.body().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.readTree(trimmed);
                } catch (Exception e) {
                    continue;
                }
                if (node.has("data") && node.get("data").isTextual()) {
                    out.writeBytes(Base64.getDecoder().decode(node.get("data").asText()));
                }
                int code = node.path("code").asInt(-1);
                if (code == SUCCESS_CODE) {
                    success = true;
                } else if (node.has("message") && code > 0) {
                    log.warn("SeedTTS 返回错误 code={} msg={}", code, node.path("message").asText());
                }
            }
            byte[] audio = out.toByteArray();
            if (audio.length == 0) {
                log.warn("SeedTTS 未返回音频（success={}）", success);
                return null;
            }
            return audio;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("SeedTTS 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
