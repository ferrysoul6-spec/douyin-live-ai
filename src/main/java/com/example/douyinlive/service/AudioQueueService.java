package com.example.douyinlive.service;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.douyinlive.config.AppProperties;

/**
 * 待播报音频队列。
 *
 * <p>用途：把 AI 生成的语音按顺序交给本机播放器（start-live.sh 内嵌），
 * 播放器用 afplay 播放，声音通过 OBS/直播伴侣的「桌面音频」或虚拟声卡混进直播间，
 * 这样所有观众都能听到 AI 说话——这是最接近“AI 主播开口”的合规做法。
 *
 * <p>队列有上限，超出时丢最旧的：直播场景里过时的语音不如不说。
 * 单实例内存实现；多实例部署需换 Redis List。
 */
@Service
public class AudioQueueService {

    private static final Logger log = LoggerFactory.getLogger(AudioQueueService.class);

    private final AppProperties props;
    private final ConcurrentLinkedDeque<AudioTask> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger totalEnqueued = new AtomicInteger();
    private final AtomicInteger totalDropped = new AtomicInteger();

    public AudioQueueService(AppProperties props) {
        this.props = props;
    }

    /** 入队一条待播报音频；返回是否成功入队。 */
    public boolean enqueue(String fileName, String text, String toNickname) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        int max = props.getTts().getQueueMaxSize();
        queue.addLast(new AudioTask(fileName, text, toNickname, System.currentTimeMillis()));
        totalEnqueued.incrementAndGet();

        // 超上限丢最旧
        while (queue.size() > max) {
            AudioTask dropped = queue.pollFirst();
            if (dropped != null) {
                totalDropped.incrementAndGet();
                log.info("音频队列已满({})，丢弃最旧一条: {}", max, dropped.fileName());
            }
        }
        return true;
    }

    /** 播放器取走下一条（破坏性弹出）；空队列返回 null。 */
    public AudioTask poll() {
        return queue.pollFirst();
    }

    /** 只看不取，用于调试面板展示。 */
    public List<AudioTask> peek(int limit) {
        return queue.stream().limit(limit).toList();
    }

    public int size() {
        return queue.size();
    }

    public void clear() {
        int n = queue.size();
        queue.clear();
        log.info("音频队列已清空，丢弃 {} 条", n);
    }

    public int totalEnqueued() {
        return totalEnqueued.get();
    }

    public int totalDropped() {
        return totalDropped.get();
    }

    /**
     * @param fileName   音频文件名（位于 TTS 缓存目录）
     * @param text       对应文案，播放器可同步显示字幕
     * @param toNickname 针对的观众昵称
     * @param enqueueAt  入队时间戳，播放器可据此丢弃过旧任务
     */
    public record AudioTask(String fileName, String text, String toNickname, long enqueueAt) {}
}
