package com.example.douyinlive.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.douyinlive.llm.TtsClient;
import com.example.douyinlive.service.AudioQueueService;

/**
 * 音频下发与播放队列接口。
 *
 * <p>/audio/{name}.mp3 供播放器或任意调用方获取音频；
 * /audio/queue/* 供本机播放器（混流方案，全直播间可听）使用。
 */
@RestController
@RequestMapping("/audio")
public class AudioController {

    private static final Logger log = LoggerFactory.getLogger(AudioController.class);

    private final TtsClient ttsClient;
    private final AudioQueueService queue;

    public AudioController(TtsClient ttsClient, AudioQueueService queue) {
        this.ttsClient = ttsClient;
        this.queue = queue;
    }

    /** 取音频文件；name 只允许文件名，防目录穿越。 */
    @GetMapping(value = "/{name}", produces = "audio/mpeg")
    public ResponseEntity<Resource> getAudio(@PathVariable String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Path file = ttsClient.cacheDir().resolve(name).normalize();
        if (!file.startsWith(ttsClient.cacheDir()) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .contentLength(Files.size(file))
                    .body(new FileSystemResource(file));
        } catch (IOException e) {
            log.warn("读取音频失败 {}: {}", name, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 播放器取下一条（弹出）。空队列返回 204。 */
    @GetMapping("/queue/next")
    public ResponseEntity<Map<String, Object>> next() {
        AudioQueueService.AudioTask task = queue.poll();
        if (task == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of(
                "fileName", task.fileName(),
                "url", "/audio/" + task.fileName(),
                "text", task.text() == null ? "" : task.text(),
                "toNickname", task.toNickname() == null ? "" : task.toNickname(),
                "enqueueAt", task.enqueueAt()
        ));
    }

    /** 只看不取，调试用。 */
    @GetMapping("/queue")
    public Map<String, Object> peek(@RequestParam(defaultValue = "10") int limit) {
        List<AudioQueueService.AudioTask> items = queue.peek(limit);
        return Map.of(
                "size", queue.size(),
                "totalEnqueued", queue.totalEnqueued(),
                "totalDropped", queue.totalDropped(),
                "items", items
        );
    }

    /** 清空队列（直播中途想立刻止损 AI 语音时用）。 */
    @DeleteMapping("/queue")
    public Map<String, Object> clear() {
        queue.clear();
        return Map.of("ok", true, "size", queue.size());
    }

    /** 手动触发一句语音播报，联调时用。 */
    @PostMapping("/speak")
    public Map<String, Object> speak(@RequestParam String text) {
        if (text == null || text.isBlank()) {
            return Map.of("ok", false, "message", "文本为空");
        }
        try {
            return ttsClient.synthesize(text)
                    .map(name -> {
                        boolean queued = queue.enqueue(name, text, null);
                        return Map.<String, Object>of(
                                "ok", true,
                                "fileName", name,
                                "url", "/audio/" + name,
                                "queued", queued);
                    })
                    .orElseGet(() -> Map.of("ok", false, "message", "TTS 不可用或合成失败"));
        } catch (RuntimeException e) {
            // 手动播报是联调口，失败要给出原因而不是 500
            log.warn("手动播报失败: {}", e.getMessage());
            return Map.of("ok", false, "message", "TTS 合成异常: " + e.getMessage());
        }
    }
}
