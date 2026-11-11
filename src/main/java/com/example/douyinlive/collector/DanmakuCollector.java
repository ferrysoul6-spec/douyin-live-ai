package com.example.douyinlive.collector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.douyinlive.DouyinLiveClient;
import com.douyinlive.event.ChatEvent;
import com.douyinlive.event.GiftComboEvent;
import com.douyinlive.event.GiftComboState;
import com.douyinlive.event.GiftEvent;
import com.douyinlive.event.LikeEvent;
import com.douyinlive.event.MemberEvent;
import com.douyinlive.event.SocialEvent;
import com.douyinlive.event.UserEvent;
import com.douyinlive.http.SignProvider;
import com.douyinlive.listener.DouyinLiveListener;
import com.douyinlive.model.DouyinUser;
import com.example.douyinlive.config.AppProperties;
import com.example.douyinlive.model.LiveEvent;
import com.example.douyinlive.service.EventWorker;
import com.example.douyinlive.service.ReplyService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 弹幕采集端：连接真实抖音直播间，把 DouyinLiveJava 的事件转成统一的 LiveEvent，
 * 投递给 EventWorker 异步处理（LLM 一次要 1s 左右，不能阻塞 SDK 的读线程）。
 *
 * <p>SDK 不含签名算法，需要外部签名服务：
 * - live-ai.collector.sign-url 配自建签名服务地址
 * - 或留空 + 环境变量 RAPIDAPI_KEY（SDK 的 SignProvider.fromConfig 自动识别）
 *
 * <p>断线自动重连：起始间隔 reconnect-delay-seconds，失败翻倍，封顶 reconnect-max-seconds。
 */
@Component
public class DanmakuCollector {

    private static final Logger log = LoggerFactory.getLogger(DanmakuCollector.class);

    private final AppProperties props;
    private final ReplyService replyService;
    private final EventWorker eventWorker;

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 重连中标志：SDK 可能连续回调多次 onDisconnect，避免并发跑多个重连线程。 */
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    /**
     * 连击去重：SDK 会同时给 onGift 和 onGiftCombo，同一个 groupId 只该感谢一次。
     * value 是上次投递时间，超过窗口就清掉，避免长时间运行无限涨。
     */
    private final Map<String, Long> thankedGiftGroups = new ConcurrentHashMap<>();
    private volatile DouyinLiveClient client;

    public DanmakuCollector(AppProperties props, ReplyService replyService, EventWorker eventWorker) {
        this.props = props;
        this.replyService = replyService;
        this.eventWorker = eventWorker;
    }

    @PostConstruct
    void start() {
        String mode = props.getCollector().getMode();
        switch (mode == null ? "off" : mode) {
            case "simulate" -> new DanmakuSimulator(replyService).run();
            case "live" -> startLive();
            default -> log.info("弹幕采集未启动（live-ai.collector.mode=off），仅提供 HTTP 观测接口");
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        DouyinLiveClient c = client;
        if (c != null) {
            c.disconnect();
        }
    }

    private void startLive() {
        AppProperties.Collector cfg = props.getCollector();
        if ((cfg.getLiveId() == null || cfg.getLiveId().isBlank())
                && (cfg.getRoomId() == null || cfg.getRoomId().isBlank())) {
            log.warn("mode=live 但未配置 live-id 或 room-id，弹幕采集不启动");
            return;
        }
        if (!hasSignConfig(cfg)) {
            log.error("mode=live 但缺少签名服务配置，弹幕采集不启动（SDK 不含签名算法）。三选一：\n"
                    + "  1. export RAPIDAPI_KEY=<X-RapidAPI-Key>  ← 订阅 {} \n"
                    + "  2. 配置 live-ai.collector.sign-url 指向自建签名服务\n"
                    + "  3. export SIGN_URL=... 与 SIGN_API_KEY=...（独立站直连）\n"
                    + "或用 scripts/start-live.sh 启动，它会做同样的校验。",
                    SignProvider.RAPIDAPI_LISTING_URL);
            return;
        }
        running.set(true);
        Thread worker = new Thread(this::connectLoop, "danmaku-collector");
        worker.setDaemon(true);
        worker.start();
    }

    /** 签名服务配置是否可用：sign-url / RAPIDAPI_KEY / SIGN_API_KEY 任一存在即可。 */
    private boolean hasSignConfig(AppProperties.Collector cfg) {
        return (cfg.getSignUrl() != null && !cfg.getSignUrl().isBlank())
                || envPresent("RAPIDAPI_KEY")
                || envPresent("SIGN_API_KEY");
    }

    private boolean envPresent(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank();
    }

    /** 连接 + 指数退避重连，直到 stop()。 */
    private void connectLoop() {
        long delayMs = props.getCollector().getReconnectDelaySeconds() * 1000L;
        long maxMs = props.getCollector().getReconnectMaxSeconds() * 1000L;
        while (running.get()) {
            try {
                DouyinLiveClient c = buildClient();
                c.addListener(new DanmakuListener());
                this.client = c;
                c.connect();
                log.info("弹幕采集已连接（连接保持中，断线将自动重连）");
                return; // connect() 阻塞直到握手完成；后续断线由 onDisconnect 触发重连
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("弹幕采集连接失败（签名服务不可用或已过期？），{}ms 后重试: {}",
                        delayMs, e.getMessage());
                sleepQuietly(delayMs);
                delayMs = Math.min(delayMs * 2, maxMs);
            }
        }
    }

    private DouyinLiveClient buildClient() {
        AppProperties.Collector cfg = props.getCollector();
        String cookie = cfg.getCookie();
        if (cfg.getSignUrl() != null && !cfg.getSignUrl().isBlank()) {
            // 自建签名服务：new DouyinLiveClient(liveId, signUrl[, cookie])
            if (cfg.getRoomId() != null && !cfg.getRoomId().isBlank()) {
                return cookie != null
                        ? DouyinLiveClient.byRoomId(cfg.getRoomId(), cfg.getSignUrl(), cookie)
                        : DouyinLiveClient.byRoomId(cfg.getRoomId(), cfg.getSignUrl());
            }
            return cookie != null
                    ? new DouyinLiveClient(cfg.getLiveId(), cfg.getSignUrl(), cookie)
                    : new DouyinLiveClient(cfg.getLiveId(), cfg.getSignUrl());
        }
        // RapidAPI / 环境变量签名：SignProvider.fromConfig(System::getenv)
        SignProvider provider = SignProvider.fromConfig(System::getenv);
        if (cfg.getRoomId() != null && !cfg.getRoomId().isBlank()) {
            return cookie != null
                    ? DouyinLiveClient.byRoomId(cfg.getRoomId(), provider, cookie)
                    : DouyinLiveClient.byRoomId(cfg.getRoomId(), provider);
        }
        return cookie != null
                ? new DouyinLiveClient(cfg.getLiveId(), provider, cookie)
                : new DouyinLiveClient(cfg.getLiveId(), provider);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把 SDK 事件映射成 LiveEvent 后投递给处理线程，本方法必须立刻返回。 */
    private void dispatch(UserEvent source, LiveEvent.Type type, String content, Integer count) {
        try {
            DouyinUser user = source.user();
            LiveEvent event = new LiveEvent();
            event.setType(type);
            event.setOpenid(user == null ? null : String.valueOf(user.id()));
            event.setNickname(user == null ? null : user.nickname());
            event.setContent(content);
            event.setCount(count);
            event.setRoomId(source.roomId());
            event.setTimestamp(System.currentTimeMillis());

            if (!eventWorker.submit(event)) {
                log.debug("事件未入队（队列已满或已停止），丢弃: type={} nickname={}",
                        type, event.getNickname());
            }
        } catch (RuntimeException e) {
            log.warn("事件投递异常（不影响后续弹幕）: {}", e.getMessage());
        }
    }

    /**
     * 这一帧礼物要不要跳过。
     *
     * <p>{@code repeatEnd} 只有「连击最后一帧」才置 1，而单次送礼（尤其大礼物）在协议里
     * 常常不带这个字段。原来只认 {@code isComboEnd()}，等于把单次礼物全当「连击未结束」
     * 丢掉——线上一条礼物感谢都没出。
     *
     * <p>现在的判据：结束帧一定放行；数量≤1 说明不是连击，也放行；
     * 只有「还在连击中、且已经累计多个」才等结束帧。
     */
    static boolean shouldSkipGiftFrame(boolean comboEnd, long repeatCount, long comboCount) {
        if (comboEnd) {
            return false;
        }
        return repeatCount > 1 || comboCount > 1;
    }

    /**
     * 同一个礼物连击只感谢一次。
     *
     * <p>SDK 会同时投 onGift（逐帧）和 onGiftCombo（合并后），不去重就是一条礼物感谢两三遍。
     * 没有 groupId（0/空）的礼物无从判断，宁可重复也不漏，直接放行。
     */
    private boolean alreadyThanked(String group) {
        if (group == null || group.isBlank() || "0".equals(group) || "null".equals(group)) {
            return false;
        }
        long now = System.currentTimeMillis();
        thankedGiftGroups.entrySet().removeIf(e -> now - e.getValue() > 10_000L);
        return thankedGiftGroups.putIfAbsent(group, now) != null;
    }

    /** SDK 监听器：只覆写我们关心的事件。 */
    private class DanmakuListener implements DouyinLiveListener {

        @Override
        public void onConnect(String roomId, String title) {
            log.info("已连入直播间 roomId={} title={}", roomId, title);
        }

        @Override
        public void onChat(ChatEvent event) {
            dispatch(event, LiveEvent.Type.COMMENT, event.content(), null);
        }

        @Override
        public void onMember(MemberEvent event) {
            dispatch(event, LiveEvent.Type.ENTER, null, (int) event.memberCount());
        }

        @Override
        public void onGift(GiftEvent event) {
            if (shouldSkipGiftFrame(event.isComboEnd(), event.repeatCount(), event.comboCount())) {
                log.debug("礼物连击进行中，等结束帧: {} x{}", event.giftName(), event.repeatCount());
                return;
            }
            String group = String.valueOf(event.groupId());
            if (alreadyThanked(group)) {
                log.debug("该连击已感谢过，跳过重复事件: group={} gift={}", group, event.giftName());
                return;
            }
            dispatch(event, LiveEvent.Type.GIFT, event.giftName(),
                    (int) Math.max(1, event.repeatCount()));
        }

        /** SDK 合并后的连击事件：只认 FINISHED，ONGOING 会一直刷新。 */
        @Override
        public void onGiftCombo(GiftComboEvent event) {
            if (event.state() != GiftComboState.FINISHED) {
                return;
            }
            String group = String.valueOf(event.groupId());
            if (alreadyThanked(group)) {
                return;
            }
            dispatch(event, LiveEvent.Type.GIFT, event.giftName(),
                    (int) Math.max(1, event.repeatCount()));
        }

        @Override
        public void onLike(LikeEvent event) {
            // 点赞是高频事件（观众连点几十上百下），这里只负责投递；
            // 文案与节流在 ReplyService：攒够数量、冷却到期才播一次。
            long count = event.count() > 0 ? event.count() : 1;
            dispatch(event, LiveEvent.Type.LIKE, null, (int) Math.min(count, 999));
        }

        @Override
        public void onSocial(SocialEvent event) {
            dispatch(event, LiveEvent.Type.FOLLOW, null, null);
        }

        @Override
        public void onDisconnect(int code, String reason, boolean remote) {
            log.warn("弹幕连接断开 code={} reason={} remote={}，将自动重连", code, reason, remote);
            if (!running.get()) {
                return;
            }
            if (!reconnecting.compareAndSet(false, true)) {
                log.debug("重连线程已在运行，忽略本次重复的断开回调");
                return;
            }
            Thread worker = new Thread(() -> {
                try {
                    sleepQuietly(props.getCollector().getReconnectDelaySeconds() * 1000L);
                    if (running.get()) {
                        connectLoop();
                    }
                } finally {
                    reconnecting.set(false);
                }
            }, "danmaku-reconnect");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void onError(Throwable error) {
            log.warn("弹幕连接错误: {}", error.getMessage());
        }
    }
}
