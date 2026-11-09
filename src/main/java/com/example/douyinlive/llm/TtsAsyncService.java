package com.example.douyinlive.llm;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.douyinlive.config.AppProperties;
import com.example.douyinlive.service.AudioQueueService;

import jakarta.annotation.PreDestroy;

/**
 * 语音合成异步化：把 1-3s 的合成从回复链路里挪出去。
 *
 * <p>行为：
 * <ul>
 *   <li>缓存命中 → 同步返回文件名，调用方立刻带上 audioUrl（实测 &lt;20ms）</li>
 *   <li>未命中 → 提交后台任务合成，完成后入播放队列；本次回复先只发文本</li>
 * </ul>
 *
 * <p>防重：同一句话正在合成时不会重复提交（直播里相邻弹幕经常触发同一句欢迎语）。
 * 线程数与队列上限可配，队列满时直接丢弃——直播场景丢语音比阻塞主线程好。
 */
@Service
public class TtsAsyncService {

    private static final Logger log = LoggerFactory.getLogger(TtsAsyncService.class);

    private final AppProperties props;
    private final TtsClient ttsClient;
    private final AudioQueueService audioQueue;
    private final ExecutorService executor;
    /** 实际生效的线程数与队列容量（配置为 0 时会被抬到下限，stats 必须报真实值）。 */
    private final int threads;
    private final int queueSize;

    /** 正在合成的文本，避免同一句被并发重复合成。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger submitted = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger deduped = new AtomicInteger();

    public TtsAsyncService(AppProperties props, TtsClient ttsClient, AudioQueueService audioQueue) {
        this.props = props;
        this.ttsClient = ttsClient;
        this.audioQueue = audioQueue;
        this.threads = Math.max(1, props.getTts().getAsyncThreads());
        this.queueSize = Math.max(4, props.getTts().getAsyncQueueSize());
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize),
                r -> {
                    Thread t = new Thread(r, "tts-async-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 队列满直接丢弃：宁可少一条语音，也不要拖慢弹幕处理线程
                (r, ex) -> log.warn("TTS 异步队列已满（{}），丢弃本次合成", queueSize));
        log.info("TTS 异步模式：threads={} queue={} enabled={}",
                threads, queueSize, props.getTts().isAsyncEnabled());
    }

    /**
     * 回复链路调用：命中缓存则返回文件名（调用方拼 audioUrl），否则后台合成并返回 empty。
     */
    public Optional<String> cachedOrDispatch(String text, String toNickname) {
        if (!props.getTts().isEnabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (!ttsClient.available()) {
            return Optional.empty();
        }

        // 1. 缓存命中：同步返回，秒回
        Optional<String> hit = ttsClient.cached(text);
        if (hit.isPresent()) {
            enqueueForPlayer(hit.get(), text, toNickname);
            return hit;
        }

        // 2. 未命中：异步合成（关闭异步时退回同步，保持可回滚）
        if (!props.getTts().isAsyncEnabled()) {
            Optional<String> synced = ttsClient.synthesize(text);
            synced.ifPresent(f -> enqueueForPlayer(f, text, toNickname));
            return synced;
        }
        dispatch(text, toNickname);
        return Optional.empty();
    }

    /** 后台合成：成功则写盘并入播放队列。 */
    private void dispatch(String text, String toNickname) {
        if (!inFlight.add(text)) {
            deduped.incrementAndGet();
            log.debug("同一句正在合成，跳过重复提交: {}", abbreviate(text));
            return;
        }
        submitted.incrementAndGet();
        try {
            executor.execute(() -> {
                long start = System.currentTimeMillis();
                try {
                    Optional<String> fileName = ttsClient.synthesize(text);
                    if (fileName.isPresent()) {
                        completed.incrementAndGet();
                        enqueueForPlayer(fileName.get(), text, toNickname);
                        log.info("TTS 异步合成完成 {}（{}ms）: {}",
                                fileName.get(), System.currentTimeMillis() - start, abbreviate(text));
                    } else {
                        failed.incrementAndGet();
                        log.warn("TTS 异步合成失败（{}ms）: {}",
                                System.currentTimeMillis() - start, abbreviate(text));
                    }
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                    log.warn("TTS 异步合成异常: {}", e.getMessage());
                } finally {
                    inFlight.remove(text);
                }
            });
        } catch (RuntimeException e) {
            // 提交失败（如执行器已关闭）：撤销占位
            inFlight.remove(text);
            log.warn("TTS 任务提交失败: {}", e.getMessage());
        }
    }

    private void enqueueForPlayer(String fileName, String text, String toNickname) {
        if (!props.getTts().isEnqueueForPlayer()) {
            return;
        }
        try {
            audioQueue.enqueue(fileName, text, toNickname);
        } catch (RuntimeException e) {
            log.warn("音频入队失败: {}", e.getMessage());
        }
    }

    /** 观测指标：提交/完成/失败/去重次数。 */
    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("asyncEnabled", props.getTts().isAsyncEnabled());
        m.put("threads", threads);
        m.put("submitted", submitted.get());
        m.put("completed", completed.get());
        m.put("failed", failed.get());
        m.put("deduped", deduped.get());
        m.put("inFlight", inFlight.size());
        return m;
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
