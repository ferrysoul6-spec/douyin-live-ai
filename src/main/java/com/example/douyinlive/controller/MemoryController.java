package com.example.douyinlive.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.douyinlive.config.AppProperties;
import com.example.douyinlive.llm.TtsAsyncService;
import com.example.douyinlive.service.EventWorker;
import com.example.douyinlive.service.ViewerMemoryService;

/**
 * 记忆观测接口：看 AI 记住了什么、手动落盘。
 */
@RestController
@RequestMapping("/memory")
public class MemoryController {

    private final ViewerMemoryService memory;
    private final AppProperties props;
    private final TtsAsyncService ttsAsync;
    private final EventWorker eventWorker;

    public MemoryController(ViewerMemoryService memory, AppProperties props,
                            TtsAsyncService ttsAsync, EventWorker eventWorker) {
        this.memory = memory;
        this.props = props;
        this.ttsAsync = ttsAsync;
        this.eventWorker = eventWorker;
    }

    /** 事件处理队列：积压 / 收到 / 已处理 / 失败 / 丢弃。 */
    @GetMapping("/pipeline")
    public Map<String, Object> pipeline() {
        return eventWorker.stats();
    }

    /** 语音异步合成指标：提交/完成/失败/去重/in-flight。 */
    @GetMapping("/tts")
    public Map<String, Object> tts() {
        return ttsAsync.stats();
    }

    /** 记忆运行统计：档案数、累计访问、老观众访问、落盘路径。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return memory.stats();
    }

    /** 查看单个观众的档案（不产生访问记录）。 */
    @GetMapping("/viewer/{openid}")
    public Object viewer(@PathVariable String openid) {
        ViewerMemoryService.ViewerProfile p = memory.peek(openid);
        return p == null ? Map.of("found", false) : Map.of("found", true, "profile", p);
    }

    /** 当前生效的人设记忆（排查 prompt 是否带上）。 */
    @GetMapping("/persona")
    public Map<String, Object> persona() {
        AppProperties.Persona p = props.getPersona();
        String file = p.getPersonaMemoryFile();
        return Map.of(
                "name", p.getName(),
                "style", p.getStyle(),
                "personaMemoryFile", file == null ? "" : file,
                "personaMemoryChars", p.getPersonaMemory() == null ? 0 : p.getPersonaMemory().length()
        );
    }

    /** 立即落盘（直播结束前调用可确保记忆不丢）。 */
    @PostMapping("/flush")
    public Map<String, Object> flush() {
        memory.flush();
        return Map.of("ok", true, "stats", memory.stats());
    }
}
