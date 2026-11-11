package com.example.douyinlive.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;

/**
 * 频控与去重。
 *
 * <p>直播间弹幕量远大于能处理的量，必须三道闸：
 * 1. 同一用户欢迎语去重（避免反复“欢迎XX”刷屏）；
 * 2. 同一用户 AI 回复冷却（避免一个人连续提问占满额度）；
 * 3. 全局每分钟 LLM 调用上限（控成本 + 控延迟）。
 *
 * <p>全部用内存实现，单实例够用；多实例部署需换 Redis（抖音云有 Redis 组件）。
 */
@Component
public class RateLimiter {

    private final AppProperties props;

    /** key = roomId:openid，value = 上次欢迎时间戳。 */
    private final Map<String, Long> lastWelcome = new ConcurrentHashMap<>();
    /** key = roomId:openid，value = 上次 AI 回复时间戳。 */
    private final Map<String, Long> lastReply = new ConcurrentHashMap<>();
    /** 表演类口令（打1看舞狮头）的全局冷却时刻。 */
    private final AtomicLong lastShowAt = new AtomicLong();
    /** 全局滑动窗口：记录最近一分钟内的 LLM 调用时间戳。 */
    private final Deque<Long> llmWindow = new ArrayDeque<>();
    /** 进场欢迎专用窗口。 */
    private final Deque<Long> welcomeWindow = new ArrayDeque<>();
    /** 评论回复专用窗口。 */
    private final Deque<Long> replyWindow = new ArrayDeque<>();
    /** 欢迎语总量窗口（LLM + 模板合计）。 */
    private final Deque<Long> welcomeBroadcastWindow = new ArrayDeque<>();

    public RateLimiter(AppProperties props) {
        this.props = props;
    }

    /** 是否应该给这个用户发欢迎语；返回 true 表示本次允许并已记录。 */
    public boolean tryWelcome(String roomId, String openid) {
        String key = key(roomId, openid);
        long now = System.currentTimeMillis();
        long windowMs = props.getThrottle().getWelcomeDedupeSeconds() * 1000L;
        Long prev = lastWelcome.get(key);
        if (prev != null && now - prev < windowMs) {
            return false;
        }
        lastWelcome.put(key, now);
        return true;
    }

    /** 该用户是否已过回复冷却期；返回 true 表示允许并已记录。 */
    public boolean tryReply(String roomId, String openid) {
        String key = key(roomId, openid);
        long now = System.currentTimeMillis();
        long cooldownMs = props.getThrottle().getReplyCooldownSeconds() * 1000L;
        Long prev = lastReply.get(key);
        if (prev != null && now - prev < cooldownMs) {
            return false;
        }
        lastReply.put(key, now);
        return true;
    }

    /**
     * 取一个「表演口令」名额（打1看舞狮头这类）。
     *
     * <p>口令是全场合唱式的：一个人发「1」会带动一片人跟着发，而且每次要播一个
     * 十秒的动作。不做全局冷却就会连着播十几遍，画面和弹幕一起被刷满。
     * 冷却之内的口令直接丢弃，不回复也不表演。
     */
    public boolean tryShow() {
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0, props.getThrottle().getShowCooldownSeconds()) * 1000L;
        long prev = lastShowAt.get();
        if (prev != 0 && now - prev < cooldownMs) {
            return false;
        }
        lastShowAt.set(now);
        return true;
    }

    /**
     * 取一个欢迎语发送名额（LLM 生成与模板都算）。
     *
     * <p>欢迎去重只管「同一个人」，挡不住开场几十个不同观众各来一条；
     * 这里按总量节流，超出的直接不发言，避免弹幕被欢迎语刷满。
     */
    public synchronized boolean tryBroadcastWelcome() {
        long now = System.currentTimeMillis();
        evictBefore(welcomeBroadcastWindow, now - 60_000L);
        int limit = props.getThrottle().getWelcomePerMinute();
        if (welcomeBroadcastWindow.size() >= limit) {
            return false;
        }
        welcomeBroadcastWindow.addLast(now);
        return true;
    }

    /**
     * 取一个 LLM 名额。
     *
     * <p>欢迎语与评论回复分开记账：开场进场高峰会用掉大量欢迎额度，
     * 如果和评论共用同一个窗口，真实提问会因「额度已满」被降到话术池。
     *
     * @param welcome true=进场欢迎，false=评论回复
     * @return true 表示已占用一个名额
     */
    public synchronized boolean tryAcquireLlm(boolean welcome) {
        long now = System.currentTimeMillis();
        evictExpired(now);
        var cfg = props.getThrottle();
        Deque<Long> scoped = welcome ? welcomeWindow : replyWindow;
        int scopedLimit = welcome ? cfg.getWelcomeLlmPerMinute() : cfg.getReplyLlmPerMinute();
        if (llmWindow.size() >= cfg.getGlobalLlmPerMinute() || scoped.size() >= scopedLimit) {
            return false;
        }
        llmWindow.addLast(now);
        scoped.addLast(now);
        return true;
    }

    private void evictExpired(long now) {
        long cutoff = now - 60_000L;
        evictBefore(llmWindow, cutoff);
        evictBefore(welcomeWindow, cutoff);
        evictBefore(replyWindow, cutoff);
        evictBefore(welcomeBroadcastWindow, cutoff);
    }

    private void evictBefore(Deque<Long> window, long cutoff) {
        while (!window.isEmpty() && window.peekFirst() < cutoff) {
            window.pollFirst();
        }
    }

    /** 定期清理长时间不活跃的 map 条目，防止内存无限增长。 */
    @Scheduled(fixedDelay = 600_000L)
    void cleanup() {
        long now = System.currentTimeMillis();
        long welcomeTtl = props.getThrottle().getWelcomeDedupeSeconds() * 2000L;
        long replyTtl = props.getThrottle().getReplyCooldownSeconds() * 2000L;
        lastWelcome.entrySet().removeIf(e -> now - e.getValue() > welcomeTtl);
        lastReply.entrySet().removeIf(e -> now - e.getValue() > replyTtl);
    }

    private String key(String roomId, String openid) {
        return (roomId == null ? "_" : roomId) + ":" + (openid == null ? "anon" : openid);
    }
}
