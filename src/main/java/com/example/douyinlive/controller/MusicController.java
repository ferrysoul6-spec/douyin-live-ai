package com.example.douyinlive.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.douyinlive.service.MusicService;

/**
 * 点歌队列的播放器接口。
 *
 * <p>由 scripts/start-live.sh 的 music 循环调用：
 * 取歌 → 下载 → afplay → 播完回调 finish → 再来取；播放期间轮询 skip 支持切歌。
 */
@RestController
@RequestMapping("/music")
public class MusicController {

    private final MusicService music;

    public MusicController(MusicService music) {
        this.music = music;
    }

    /** 取下一首；队列空返回 204。 */
    @GetMapping("/next")
    public ResponseEntity<Map<String, Object>> next() {
        Optional<MusicService.Song> song = music.takeNext();
        if (song.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", song.get().title());
        body.put("url", song.get().url());
        body.put("by", song.get().requestedBy() == null ? "" : song.get().requestedBy());
        return ResponseEntity.ok(body);
    }

    /** 当前这首播完了，清掉「正在播」。 */
    @PostMapping("/finish")
    public Map<String, Object> finish() {
        music.finishCurrent();
        return Map.of("ok", true);
    }

    /** 是否需要中断当前播放（一次性信号）。 */
    @GetMapping("/skip")
    public Map<String, Object> skip() {
        return Map.of("skip", music.consumeSkip());
    }

    /** 手动点一首（联调用，等价于观众发「点歌 X」）。 */
    @PostMapping("/request")
    public Map<String, Object> request(@RequestParam String q) {
        MusicService.EnqueueResult r = music.enqueue(q, "手动");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", r.ok());
        body.put("full", r.full());
        body.put("title", r.title());
        return body;
    }

    /** 手动切歌。 */
    @PostMapping("/skip")
    public Map<String, Object> skipNow() {
        return Map.of("ok", music.requestSkip());
    }

    /** 队列与正在播。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return music.state();
    }
}
