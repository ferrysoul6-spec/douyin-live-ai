package com.example.douyinlive.llm;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 语音合成抽象。SeedTTS（火山豆包）与 edge（微软免费）各一个实现，
 * 由 FallbackTtsClient 按 live-ai.tts.provider 路由，业务代码不感知。
 */
public interface TtsClient {

    /**
     * 把文本合成为音频文件（阻塞，耗时 1-3s）。
     *
     * @param text 要播报的文本
     * @return 音频文件名（含扩展名，位于音频目录内）；失败返回 empty，调用方降级为纯文本
     */
    Optional<String> synthesize(String text);

    /**
     * 只查缓存不合成：命中返回文件名，未命中返回 empty。
     * 异步链路用它实现「命中秒回、未命中后台合成」。
     */
    Optional<String> cached(String text);

    /** 该实现是否可用（依赖缺失、Key 未配时返回 false）。 */
    boolean available();

    /** 音频缓存目录，供 AudioController 下发文件时做目录穿越校验。 */
    Path cacheDir();
}
