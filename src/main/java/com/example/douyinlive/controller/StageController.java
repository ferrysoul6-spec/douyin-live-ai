package com.example.douyinlive.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.douyinlive.service.StageService;

/**
 * 直播画面页 + 状态接口。
 *
 * <p>OBS 用「浏览器源」指向 {@code http://127.0.0.1:8080/stage}：
 * 页面透明底，只画角色动作、字幕气泡和点歌队列，叠在直播伴侣的推流画面上。
 */
@RestController
public class StageController {

    private static final Logger log = LoggerFactory.getLogger(StageController.class);
    private static final Path PAGE = Path.of("live-assets/stage/index.html");

    private final StageService stage;

    public StageController(StageService stage) {
        this.stage = stage;
    }

    /** 返回舞台页（每次读文件，改页面不用重启）。 */
    @GetMapping(value = "/stage", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        try {
            if (!Files.exists(PAGE)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("找不到舞台页: " + PAGE.toAbsolutePath());
            }
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(Files.readString(PAGE, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("读取舞台页失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_PLAIN).body("读取舞台页失败");
        }
    }

    /** 当前动作、最近一句回复、点歌队列。 */
    @GetMapping("/stage/state")
    public Map<String, Object> state() {
        return stage.state();
    }
}
