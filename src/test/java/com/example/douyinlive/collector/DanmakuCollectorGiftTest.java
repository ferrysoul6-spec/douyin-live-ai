package com.example.douyinlive.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 礼物事件要不要投递。
 *
 * <p>线上出过的问题：只认 repeatEnd==1 才感谢，而单次送礼（尤其大礼物）协议里
 * 常常不带这个字段，结果礼物全被当成「连击未结束」丢掉，一条感谢都没出。
 */
class DanmakuCollectorGiftTest {

    @Test
    void 单次礼物必须放行() {
        // repeatEnd=0 且数量=1：最常见的单次送礼
        assertThat(DanmakuCollector.shouldSkipGiftFrame(false, 1, 1)).isFalse();
    }

    @Test
    void 连击中间帧等结束再感谢() {
        // 还在连击、已经累计多个：等结束帧，否则一次 20 连击会刷 20 条感谢
        assertThat(DanmakuCollector.shouldSkipGiftFrame(false, 5, 5)).isTrue();
    }

    @Test
    void 连击结束帧必须放行() {
        assertThat(DanmakuCollector.shouldSkipGiftFrame(true, 20, 20)).isFalse();
    }

    @Test
    void 结束帧即使只有一个也要放行() {
        assertThat(DanmakuCollector.shouldSkipGiftFrame(true, 1, 1)).isFalse();
    }
}
