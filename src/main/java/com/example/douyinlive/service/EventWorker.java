package com.example.douyinlive.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.douyinlive.config.AppProperties;
import com.example.douyinlive.model.LiveEvent;
import com.example.douyinlive.model.OutboundMessage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 事件处理线程：把「LLM + TTS 编排」从采集线程里挪出来。
 *
 * <p>抖音 SDK 的读线程一旦被阻塞（一次 LLM 就要 1s 左右），弹幕会在 SDK 内部堆积，
 * 严重时触发心跳超时断连。所以采集回调只负责投递，真正的处理在这里单线程串行执行：
 * 既保住了消息顺序，也保住 {@link RateLimiter} 的频控语义。
 *
 * <p>队列满时丢最旧一条——直播间宁可漏掉一条过时评论，也不能无限积压。
 */
@Component
public class EventWorker {

    private static final Logger log = LoggerFactory.getLogger(EventWorker.class);
    private static final long POLL_TIMEOUT_MS = 500L;

    private final ReplyService replyService;
    private final BlockingQueue<LiveEvent> queue;
    private final int capacity;
    private final AtomicInteger submitted = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicInteger handled = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    private volatile boolean running = true;
    private Thread thread;

    public EventWorker(AppProperties props, ReplyService replyService) {
        this.replyService = replyService;
        this.capacity = Math.max(8, props.getCollector().getEventQueueSize());
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::loop, "event-worker");
        thread.setDaemon(true);
        thread.start();
        log.info("事件处理线程已启动（队列上限 {}）", capacity);
    }

    /** 采集回调调用：只投递，不等待处理完成。 */
    public boolean submit(LiveEvent event) {
        if (!running || event == null) {
            return false;
        }
        submitted.incrementAndGet();
        if (queue.offer(event)) {
            return true;
        }
        // 队列满：丢最旧一条，尽量让最新弹幕被处理
        if (queue.poll() != null) {
            dropped.incrementAndGet();
        }
        boolean accepted = queue.offer(event);
        if (!accepted) {
            dropped.incrementAndGet();
        }
        return accepted;
    }

    private void loop() {
        while (running) {
            try {
                LiveEvent event = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    handle(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 单条事件的完整编排；任何异常都吞掉，保证后续弹幕不受影响。 */
    private void handle(LiveEvent event) {
        try {
            OutboundMessage reply = replyService.handle(event);
            handled.incrementAndGet();
            if (reply.isSuppressed()) {
                // 排查「这个观众为什么没被回复」时唯一的线索
                log.debug("[AI助播] 丢弃 {} {}: {}",
                        event.getType(), event.getNickname(), reply.getSuppressReason());
            } else {
                log.info("[AI助播] {} -> {}: {}{}（{}ms）",
                        reply.getSource(),
                        reply.getToNickname(),
                        reply.getText(),
                        reply.getAudioUrl() == null ? "" : " (语音: " + reply.getAudioUrl() + ")",
                        reply.getCostMs());
            }
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.warn("事件处理异常（不影响后续弹幕）: {}", e.getMessage());
        }
    }

    /** 观测指标，供 /memory/pipeline 展示。 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queueSize", queue.size());
        m.put("submitted", submitted.get());
        m.put("handled", handled.get());
        m.put("failed", failed.get());
        m.put("dropped", dropped.get());
        return m;
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
