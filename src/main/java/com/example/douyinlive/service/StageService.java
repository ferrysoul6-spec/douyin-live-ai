package com.example.douyinlive.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * 直播舞台状态：角色当前在做什么动作、最近一句回复、点歌队列。
 *
 * <p>前端 {@code /stage} 页面轮询 {@link #state()}，一次拿全，避免多个接口不同步。
 * 动作素材是 {@code live-assets/shenshen/} 下的逐帧 webp（深深 2.0，106 个动作），
 * 动作名与时长从同一份清单 {@code live-assets/stage/manifest-shenshen.json} 读，
 * 和前端共用，避免两边各维护一套列表。
 *
 * <p>动作是「事件驱动 + 自动回 idle」：礼物 → happy、进场 → wave、
 * 讲故事/生成回复时 → think；没有事件超过配置秒数后开始轮换待机动作。
 */
@Service
public class StageService {

    private static final Logger log = LoggerFactory.getLogger(StageService.class);

    /** 空闲轮换的小动作播放多久（毫秒），到点回 idle；auto 模式下按清单里的帧数算。 */
    private static final int IDLE_ACTION_HOLD_MS = 3_000;

    /** 非 idle 动作卡多久算异常（毫秒）：一次性的视频播完画面就定住，得拉回来。 */
    private static final long STUCK_ACTION_MS = 20_000L;

    private final AppProperties props;
    private final MusicService music;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stage-scheduler");
                t.setDaemon(true);
                return t;
            });
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong textSeq = new AtomicLong();
    /** 待机轮换池（auto 模式下是素材清单里的全部动作，不含 idle 本身）。 */
    private final List<String> idleActions;
    /** 每个动作该播多久：由清单里的帧数算出，241 帧 @24fps ≈ 10s。 */
    private final Map<String, Integer> holdMsByAction;
    /** 上一次轮换抽到的动作，避免连续两次播同一个。 */
    private volatile String lastRotateAction = "";

    private volatile String action = "idle";
    private volatile String text = "";
    private volatile String from = "";
    private volatile long lastActivityAt = System.currentTimeMillis();
    private volatile long lastRotateAt = System.currentTimeMillis();
    /** 最近一次动作切换时间，判断「卡在一次性动作上」用。 */
    private volatile long lastActionAt = System.currentTimeMillis();

    public StageService(AppProperties props, MusicService music, ObjectMapper mapper) {
        this.props = props;
        this.music = music;
        this.mapper = mapper;
        AppProperties.Stage cfg = props.getStage();
        ActionPool pool = loadPool(cfg);
        this.idleActions = pool.actions();
        this.holdMsByAction = pool.holdMs();
        log.info("舞台就绪：待机动作 {} 个，{}s 后开始轮换，间隔 {}s",
                idleActions.size(), cfg.getIdleAfterSeconds(), cfg.getIdleRotateSeconds());
    }

    /** 轮换池 + 每个动作该播多久。 */
    private record ActionPool(List<String> actions, Map<String, Integer> holdMs) {
    }

    /**
     * 装配待机动作池。
     *
     * <p>配置写 {@code auto}（默认）就从素材清单读全部动作：素材加了多少个，轮换池就有多少个，
     * 不用手工维护那一行逗号列表。动作时长也一并从帧数算出（241 帧 @24fps ≈ 10s），
     * 否则按固定 3s 播，长动作只会播个开头就被切走。
     */
    private ActionPool loadPool(AppProperties.Stage cfg) {
        String spec = cfg.getIdleActions();
        if (spec != null && !spec.isBlank() && !"auto".equalsIgnoreCase(spec.trim())) {
            List<String> names = parseActions(spec);
            Map<String, Integer> hold = new LinkedHashMap<>();
            for (String n : names) {
                hold.put(n, IDLE_ACTION_HOLD_MS);
            }
            return new ActionPool(names, hold);
        }

        Path file = Path.of(cfg.getIdleActionsFile());
        if (!Files.isReadable(file)) {
            log.warn("素材清单读不到（{}），待机轮换退化为只播 idle", file);
            return new ActionPool(List.of("idle"), Map.of("idle", IDLE_ACTION_HOLD_MS));
        }
        try (InputStream in = Files.newInputStream(file)) {
            JsonNode root = mapper.readTree(in);
            double fps = root.path("fps").asDouble(24);
            if (fps <= 0) {
                fps = 24;
            }
            JsonNode clips = root.path("clips");
            List<String> names = new ArrayList<>();
            Map<String, Integer> hold = new LinkedHashMap<>();
            Iterator<String> it = clips.fieldNames();
            while (it.hasNext()) {
                String slug = it.next();
                int frames = clips.path(slug).path("frames").asInt(0);
                // idle 是基础循环态，不放进轮换池（抽到它等于什么都不发生）
                if (frames <= 0 || "idle".equals(slug)) {
                    continue;
                }
                names.add(slug);
                // 多给 300ms：非循环动作播完停在最后一帧，别在最后一帧前就切走
                hold.put(slug, (int) Math.round(frames / fps * 1000) + 300);
            }
            if (names.isEmpty()) {
                log.warn("素材清单里没有可轮换的动作（{}），退化为只播 idle", file);
                return new ActionPool(List.of("idle"), Map.of("idle", IDLE_ACTION_HOLD_MS));
            }
            return new ActionPool(names, hold);
        } catch (Exception e) {
            log.warn("解析素材清单失败（{}）：{}，退化为只播 idle", file, e.getMessage());
            return new ActionPool(List.of("idle"), Map.of("idle", IDLE_ACTION_HOLD_MS));
        }
    }

    /** 播放一个动作，默认保持 2.6 秒后回 idle。 */
    public void play(String action) {
        play(action, 2600);
    }

    /**
     * 按素材清单里的真实时长播一个动作（241 帧 ≈ 10s），播完自动回 idle。
     *
     * <p>表演类动作要用这个而不是 2.6s 的默认值，否则只放个开头就切走。
     */
    public void playFull(String action) {
        play(action, holdMsByAction.getOrDefault(action, IDLE_ACTION_HOLD_MS));
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
        lastActionAt = System.currentTimeMillis();
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
        // 说完话就别一直「思考」了，回到待机小动作。
        // 必须排一个回切：轮换只认 action=="idle"，停在 bubble 上画面就定格了。
        if ("think".equals(action)) {
            this.action = "bubble";
            lastActionAt = System.currentTimeMillis();
            long mySeq = seq.incrementAndGet();
            scheduler.schedule(() -> {
                if (seq.get() == mySeq && "bubble".equals(this.action)) {
                    this.action = "idle";
                    seq.incrementAndGet();
                }
            }, 2_600L, TimeUnit.MILLISECONDS);
        }
    }

    /** 空闲轮换：长时间没有事件时换几个小动作，别让画面定格。 */
    @Scheduled(fixedDelay = 5_000L)
    void rotateIdle() {
        AppProperties.Stage cfg = props.getStage();
        if (!cfg.isEnabled() || idleActions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleMs = cfg.getIdleAfterSeconds() * 1000L;
        long gapMs = Math.max(5, cfg.getIdleRotateSeconds()) * 1000L;
        if (now - lastActivityAt < idleMs || now - lastRotateAt < gapMs) {
            return;
        }
        if (!"idle".equals(action)) {
            // 兜底：轮换只认 idle，一旦动作卡在别的名字上（一次性动作播完后
            // 停在最后一帧），这里就永远进不来。正常路径都会排回切任务，
            // 卡超过 STUCK_ACTION_MS 说明回切丢了，直接拉回 idle。
            if (now - lastActionAt > STUCK_ACTION_MS) {
                log.warn("动作卡在 {} 超过 {}s，强制回 idle", action, STUCK_ACTION_MS / 1000);
                action = "idle";
                seq.incrementAndGet();
            }
            return;
        }
        String next = pickIdleAction();
        lastRotateAt = now;
        lastRotateAction = next;
        if ("idle".equals(next)) {
            // 池子里只有 idle（清单没读到）：重放循环就行，不需要回切
            action = next;
            lastActionAt = System.currentTimeMillis();
            seq.incrementAndGet();
            return;
        }
        // 抽到一次性动作：按清单里的真实时长播放，播完必须切回 idle，
        // 否则 action 永远不等于 "idle"，上面的守卫会把轮换彻底锁死。
        holdThenIdle(next, holdMsByAction.getOrDefault(next, IDLE_ACTION_HOLD_MS));
    }

    /** 随机取一个，尽量不和上一次重复（池子只有一条时就直接用）。 */
    private String pickIdleAction() {
        if (idleActions.size() == 1) {
            return idleActions.get(0);
        }
        for (int i = 0; i < 8; i++) {
            String candidate = idleActions.get(ThreadLocalRandom.current().nextInt(idleActions.size()));
            if (!candidate.equals(lastRotateAction)) {
                return candidate;
            }
        }
        return idleActions.get(0);
    }

    /** 播一个一次性动作，到点自动回 idle。期间有新动作抢占则放弃。 */
    private void holdThenIdle(String next, int holdMs) {
        this.action = next;
        lastActionAt = System.currentTimeMillis();
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
