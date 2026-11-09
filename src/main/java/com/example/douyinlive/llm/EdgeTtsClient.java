package com.example.douyinlive.llm;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;

/**
 * 基于本机 edge-tts 的语音合成实现（免费、中文音色自然，适合本地联调与推流混音方案）。
 *
 * <p>通过命令行调用：
 * - command=uvx      → uvx edge-tts --text ... --voice ... --write-media ...
 * - command=edge-tts → edge-tts --text ... --voice ... --write-media ...
 *
 * <p>结果按「音色 + 文本」哈希缓存，同一句欢迎语不会重复合成。
 * 生产环境（抖音云容器）没有 uvx/python，应切到 provider=seedtts。
 */
@Component
public class EdgeTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeTtsClient.class);

    private final AppProperties props;
    private final Path cacheDir;

    /** 可执行入口探测缓存：`which` 要起进程，不能每条回复都跑一次。 */
    private volatile Boolean commandAvailable;

    public EdgeTtsClient(AppProperties props) {
        this.props = props;
        // 必须 normalize：toAbsolutePath() 会保留 "./" 段，
        // 否则 AudioController 里 file.startsWith(cacheDir) 的目录穿越校验会误判为非法路径。
        this.cacheDir = Path.of(props.getTts().getDir()).toAbsolutePath().normalize();
    }

    @Override
    public boolean available() {
        if (!props.getTts().isEnabled()) {
            return false;
        }
        Boolean cached = commandAvailable;
        if (cached != null) {
            return cached;
        }
        boolean found = resolveOnPath(props.getTts().getCommand()) != null;
        commandAvailable = found;
        if (!found) {
            log.warn("未找到 edge-tts 入口 '{}'，语音合成降级为纯文本（可切 provider=seedtts）",
                    props.getTts().getCommand());
        }
        return found;
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
        String fileName = TtsCacheKey.of(props.getTts().getVoice(), text) + ".mp3";
        File f = cacheDir.resolve(fileName).toFile();
        return (f.exists() && f.length() > 0) ? Optional.of(fileName) : Optional.empty();
    }

    @Override
    public Optional<String> synthesize(String text) {
        if (!available()) {
            return Optional.empty();
        }
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        AppProperties.Tts cfg = props.getTts();
        String fileName = TtsCacheKey.of(cfg.getVoice(), text) + ".mp3";
        File target = cacheDir.resolve(fileName).toFile();
        if (target.exists() && target.length() > 0) {
            log.debug("TTS 缓存命中: {}", fileName);
            return Optional.of(fileName);
        }

        if (!cacheDir.toFile().exists() && !cacheDir.toFile().mkdirs()) {
            log.warn("无法创建音频目录 {}", cacheDir);
            return Optional.empty();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.getCommand());
        if (!"edge-tts".equals(cfg.getCommand())) {
            cmd.add("edge-tts");
        }
        cmd.add("--text");
        cmd.add(text);
        cmd.add("--voice");
        cmd.add(cfg.getVoice());
        cmd.add("--write-media");
        cmd.add(target.getAbsolutePath());

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("TTS 合成超时（{}s），降级纯文本: {}", cfg.getTimeoutSeconds(), abbreviate(text));
                return Optional.empty();
            }
            if (p.exitValue() != 0 || !target.exists() || target.length() == 0) {
                log.warn("TTS 合成失败 exit={} text={}", p.exitValue(), abbreviate(text));
                return Optional.empty();
            }
            log.info("TTS 合成完成 {} ({} bytes)", fileName, target.length());
            return Optional.of(fileName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("TTS 调用异常: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String resolveOnPath(String command) {
        try {
            Process p = new ProcessBuilder("which", command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return (p.exitValue() == 0 && !out.isBlank() && Files.exists(Path.of(out))) ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String abbreviate(String s) {
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }
}
