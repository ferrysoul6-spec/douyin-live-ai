package com.example.douyinlive.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.douyinlive.config.AppProperties;

import jakarta.annotation.PreDestroy;

/**
 * 直播舞台状态：角色当前在做什么动作、最近一句回复、点歌队列。
 *
 * <p>前端 {@code /stage} 页面轮询 {@link #state()}，一次拿全，避免多个接口不同步。
 * 动作素材在 {@code live-assets/pet-alpha/*.webm}（真 alpha，页面直接用；缺失时退回
 * {@code live-assets/pet/*.webm} 黑底源素材 + 亮度滤镜；烘焙脚本 {@code scripts/key-black.py}
 * 负责保住内部黑线稿）。
 *
 * <p>动作是「事件驱动 + 自动回 idle」：礼物 → happy、进场 → wave、
 * 讲故事/生成回复时 → think；没有事件超过配置秒数后开始轮换 idle 动作。
 */
@Service
public class StageService {

    private static final Logger log = LoggerFactory.getLogger(StageService.class);

    /** 空闲轮换的小动作播放多久（毫秒），到点回 idle。 */
    private static final int IDLE_ACTION_HOLD_MS = 3_000;

    private final AppProperties props;
    private final MusicService music;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stage-scheduler");
                t.setDaemon(true);
                return t;
            });
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong textSeq = new AtomicLong();
    private final List<String> idleActions;

    private volatile String action = "idle";
    private volatile String text = "";
    private volatile String from = "";
    private volatile long lastActivityAt = System.currentTimeMillis();
    private volatile long lastRotateAt = System.currentTimeMillis();

    public StageService(AppProperties props, MusicService music) {
        this.props = props;
        this.music = music;
        this.idleActions = parseActions(props.getStage().getIdleActions());
        log.info("舞台就绪：空闲动作 {} 个，{}s 后开始轮换", idleActions.size(),
                props.getStage().getIdleAfterSeconds());
    }

    /** 播放一个动作，默认保持 2.6 秒后回 idle。 */
    public void play(String action) {
        play(action, 2600);
    }

    /**
     * 播放一个动作。
     *
     * @param holdMs 保持毫秒数；≤0 表示由调用方自己负责切回（如 think 等回复完成）
     */
    public void play(String action, int holdMs) {
        if (!props.getStage().isEnabled() || action == null || action.isBlank()) {
            return;
        }
        this.action = action;
        long mySeq = seq.incrementAndGet();
        lastActivityAt = System.currentTimeMillis();
        if (holdMs > 0) {
            scheduler.schedule(() -> {
                // 期间没有新动作才回 idle，避免把后一个动作打断
                if (seq.get() == mySeq && !"idle".equals(this.action)) {
                    this.action = "idle";
                    seq.incrementAndGet();
                }
            }, holdMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 记录最近一句回复，供页面字幕气泡显示。 */
    public void say(String from, String text) {
        if (!props.getStage().isEnabled() || text == null || text.isBlank()) {
            return;
        }
        this.from = from == null ? "" : from;
        this.text = text;
        textSeq.incrementAndGet();
        lastActivityAt = System.currentTimeMillis();
        // 说完话就别一直「思考」了，回到待机小动作
        if ("think".equals(action)) {
            action = "bubble";
            seq.incrementAndGet();
        }
    }

    /** 空闲轮换：长时间没有事件时换几个小动作，别让画面定格。 */
    @Scheduled(fixedDelay = 5_000L)
    void rotateIdle() {
        if (!props.getStage().isEnabled() || idleActions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleMs = props.getStage().getIdleAfterSeconds() * 1000L;
        if (now - lastActivityAt < idleMs || now - lastRotateAt < 12_000L) {
            return;
        }
        if (!"idle".equals(action)) {
            return;
        }
        String next = idleActions.get(ThreadLocalRandom.current().nextInt(idleActions.size()));
        lastRotateAt = now;
        if ("idle".equals(next)) {
            // 抽到 idle 本身：只是重放循环，不需要回切
            action = next;
            seq.incrementAndGet();
            return;
        }
        // 抽到一次性小动作：播完必须切回 idle，否则 action 永远不等于 "idle"，
        // 上面的守卫会把轮换彻底锁死（画面就定格在那一帧了）
        holdThenIdle(next, IDLE_ACTION_HOLD_MS);
    }

    /** 播一个一次性动作，到点自动回 idle。期间有新动作抢占则放弃。 */
    private void holdThenIdle(String next, int holdMs) {
        this.action = next;
        long mySeq = seq.incrementAndGet();
        scheduler.schedule(() -> {
            if (seq.get() == mySeq && next.equals(this.action)) {
                this.action = "idle";
                seq.incrementAndGet();
            }
        }, holdMs, TimeUnit.MILLISECONDS);
    }

    /** 距最近一次舞台活动过了多少毫秒（冷场暖场判静默用）。 */
    public long idleMillis() {
        return System.currentTimeMillis() - lastActivityAt;
    }

    /** 前端一次拿全：动作 + 字幕 + 点歌队列。 */
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.getStage().isEnabled());
        m.put("action", action);
        m.put("seq", seq.get());
        m.put("text", text);
        m.put("from", from);
        m.put("textSeq", textSeq.get());
        m.put("music", music.state());
        return m;
    }

    private List<String> parseActions(String csv) {
        List<String> list = new ArrayList<>();
        if (csv == null) {
            return list;
        }
        for (String part : csv.split(",")) {
            String a = part.trim();
            if (!a.isEmpty()) {
                list.add(a);
            }
        }
        return list;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
