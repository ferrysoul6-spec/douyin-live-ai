package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;
import com.example.douyinlive.model.LiveEvent;
import com.example.douyinlive.model.OutboundMessage;

class EventWorkerTest {

    private AppProperties props;
    private ReplyService replyService;
    private EventWorker worker;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        replyService = mock(ReplyService.class);
        when(replyService.handle(any())).thenReturn(
                OutboundMessage.of("回复", OutboundMessage.Display.DANMU, OutboundMessage.Source.LLM));
        worker = new EventWorker(props, replyService);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    @Test
    void 投递的事件会被处理线程消费() {
        worker.start();

        assertThat(worker.submit(comment("多少钱"))).isTrue();
        verify(replyService, timeout(2000)).handle(any());

        assertThat(worker.stats()).containsEntry("submitted", 1);
        assertThat(worker.stats()).containsEntry("failed", 0);
    }

    @Test
    void 队列满时丢最旧的一条而不是阻塞() {
        // 不启动线程，纯看队列行为；容量下限是 8
        int capacity = 8;
        props.getCollector().setEventQueueSize(capacity);
        EventWorker queueOnly = new EventWorker(props, replyService);

        for (int i = 0; i < capacity + 1; i++) {
            assertThat(queueOnly.submit(comment("第 " + i + " 条"))).isTrue();
        }

        assertThat(queueOnly.stats()).containsEntry("dropped", 1);
        assertThat(queueOnly.stats()).containsEntry("queueSize", capacity);
    }

    @Test
    void 停止后不再接受事件() {
        worker.shutdown();

        assertThat(worker.submit(comment("停止后"))).isFalse();
    }

    private LiveEvent comment(String content) {
        LiveEvent e = new LiveEvent();
        e.setType(LiveEvent.Type.COMMENT);
        e.setOpenid("u1");
        e.setNickname("测试观众");
        e.setContent(content);
        e.setRoomId("room");
        return e;
    }
}
