package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 观众记忆的并发安全：EventWorker 在写（recordQuestion）、
 * 定时任务在落盘（flush，序列化整个 profiles）、HTTP 在读，三者会撞在一起。
 */
class ViewerMemoryServiceConcurrencyTest {

    @TempDir
    Path tmp;

    @Test
    void 边写问题边落盘不应抛异常() throws Exception {
        AppProperties props = new AppProperties();
        props.getMemory().setFile(tmp.resolve("memory.json").toString());
        props.getMemory().setFlushIntervalSeconds(1);
        // 放大单个档案的问题列表，让序列化耗时变长，撞上并发修改的概率才够高
        props.getMemory().setMaxQuestionsPerViewer(3000);

        ViewerMemoryService svc = new ViewerMemoryService(props, new ObjectMapper());
        svc.load();
        svc.recordVisit("u1", "测试观众");

        AtomicInteger errors = new AtomicInteger();
        List<String> errorMessages = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> {
            await(start);
            for (int i = 0; i < 20000; i++) {
                try {
                    svc.recordQuestion("u1", "问题 " + i);
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                    errorMessages.add("write: " + e.getClass().getSimpleName());
                }
            }
        });

        pool.submit(() -> {
            await(start);
            for (int i = 0; i < 3000; i++) {
                try {
                    svc.flush();
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                    errorMessages.add("flush: " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        });

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get())
                .as("并发异常: %s", errorMessages.stream().distinct().toList())
                .isZero();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
