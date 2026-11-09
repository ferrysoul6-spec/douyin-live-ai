package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;

class AudioQueueServiceTest {

    private AppProperties props;
    private AudioQueueService queue;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        queue = new AudioQueueService(props);
    }

    @Test
    void 入队后播放器按顺序取走() {
        queue.enqueue("a.mp3", "第一句", "观众A");
        queue.enqueue("b.mp3", "第二句", "观众B");

        assertThat(queue.poll().fileName()).isEqualTo("a.mp3");
        assertThat(queue.poll().fileName()).isEqualTo("b.mp3");
        assertThat(queue.poll()).isNull();
    }

    @Test
    void 超过上限时丢弃最旧的一条() {
        int max = props.getTts().getQueueMaxSize();
        for (int i = 0; i <= max; i++) {
            queue.enqueue("audio-" + i + ".mp3", "第 " + i + " 句", null);
        }

        assertThat(queue.size()).isEqualTo(max);
        assertThat(queue.totalDropped()).isEqualTo(1);
        // 丢的是第 0 条，队首变成第 1 条
        assertThat(queue.poll().fileName()).isEqualTo("audio-1.mp3");
    }

    @Test
    void 空文件名不入队() {
        assertThat(queue.enqueue(null, "文本", null)).isFalse();
        assertThat(queue.enqueue("  ", "文本", null)).isFalse();
        assertThat(queue.size()).isZero();
    }
}
