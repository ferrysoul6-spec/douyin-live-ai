package com.example.douyinlive.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.douyinlive.config.AppProperties;

/**
 * 冷场暖场：直播间接连一段时间没有任何互动时，主动播一条预置话术。
 *
 * <p>和「空闲动作轮换」互补：轮换管画面，这里管声音和字幕。话术是预置的，
 * 不走 LLM（不烧 token），语音复用 {@link ReplyService} 的 TTS 与播放队列。
 *
 * <p>两道闸：静默不满 {@code idle-seconds} 不播；距上次暖场不足
 * {@code min-interval-seconds} 不播。暖场本身会更新舞台活动时间，
 * 所以播完自然会重新累加静默计时，不会一直自言自语。
 */
@Service
public class WarmupService {

    private static final Logger log = LoggerFactory.getLogger(WarmupService.class);

    private final AppProperties props;
    private final StageService stage;
    private final ReplyService reply;

    private final AtomicLong lastWarmupAt = new AtomicLong();
    private volatile String lastLine = "";

    public WarmupService(AppProperties props, StageService stage, ReplyService reply) {
        this.props = props;
        this.stage = stage;
        this.reply = reply;
    }

    @Scheduled(fixedDelay = 5_000L)
    void tick() {
        AppProperties.Warmup cfg = props.getWarmup();
        if (!cfg.isEnabled() || !props.getStage().isEnabled()) {
            return;
        }
        List<String> lines = cfg.getLines();
        if (lines == null || lines.isEmpty()) {
            return;
        }
        long idleMs = Math.max(0, cfg.getIdleSeconds()) * 1000L;
        if (stage.idleMillis() < idleMs) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWarmupAt.get() < Math.max(0, cfg.getMinIntervalSeconds()) * 1000L) {
            return;
        }
        String line = pick(lines);
        if (line == null || line.isBlank()) {
            return;
        }
        lastWarmupAt.set(now);
        lastLine = line;
        log.info("冷场暖场（静默 {}s）: {}", stage.idleMillis() / 1000, line);
        try {
            reply.warmup(line);
        } catch (RuntimeException e) {
            // 暖场失败不能影响直播主链路
            log.warn("暖场播报失败: {}", e.getMessage());
        }
    }

    /** 随机取一条，尽量不和上一条重复（池子只有一条时不重复就直接放弃）。 */
    private String pick(List<String> lines) {
        if (lines.size() == 1) {
            return lines.get(0).equals(lastLine) ? null : lines.get(0);
        }
        for (int i = 0; i < 6; i++) {
            String candidate = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
            if (!candidate.equals(lastLine)) {
                return candidate;
            }
        }
        return lines.get(0);
    }
}
