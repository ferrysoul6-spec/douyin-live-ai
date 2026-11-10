package com.example.douyinlive.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 观众记忆（第 2 层）：记住来过的观众，下次进场时 AI 叫得出名字、提得起历史。
 *
 * <p>存 JSON 文件，单实例够用；写盘限流（flush-interval-seconds）避免高频弹幕反复 IO。
 * 多实例部署时改成数据库即可，接口保持不变。
 */
@Service
public class ViewerMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ViewerMemoryService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final AppProperties props;
    private final ObjectMapper mapper;

    /** openid -> 观众档案 */
    private final Map<String, ViewerProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicLong lastFlushAt = new AtomicLong(0);
    private volatile boolean dirty = false;

    public ViewerMemoryService(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        if (!props.getMemory().isEnabled()) {
            log.info("观众记忆已关闭（live-ai.memory.enabled=false）");
            return;
        }
        Path file = memoryFile();
        if (!Files.exists(file)) {
            log.info("观众记忆文件不存在，将从空白开始: {}", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, ViewerProfile> loaded = mapper.readValue(json, new TypeReference<>() {});
            if (loaded != null) {
                // Jackson 反序列化会把 questions 变成 ArrayList，这里换回写时复制实现
                loaded.values().forEach(p -> p.questions = new CopyOnWriteArrayList<>(p.questions));
                profiles.putAll(loaded);
            }
            log.info("已加载观众记忆 {} 条: {}", profiles.size(), file);
        } catch (Exception e) {
            log.error("加载观众记忆失败，从空白开始: {}", e.getMessage());
        }
    }

    /**
     * 记录一次进场，返回该观众的档案快照（含本次之前的历史）。
     *
     * @return 首次来访时 visits=1 且 isReturning=false
     */
    public ViewerProfile recordVisit(String openid, String nickname) {
        if (!props.getMemory().isEnabled() || openid == null || openid.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        ViewerProfile p = profiles.computeIfAbsent(openid, k -> {
            ViewerProfile np = new ViewerProfile();
            np.firstSeen = now;
            return np;
        });
        boolean returning = p.visits > 0;
        p.visits++;
        p.lastSeen = now;
        if (nickname != null && !nickname.isBlank()) {
            p.nickname = nickname;
        }
        dirty = true;
        statVisits.incrementAndGet();
        if (returning) {
            statReturning.incrementAndGet();
        }
        ViewerProfile snapshot = p.copy();
        snapshot.isReturning = returning;
        return snapshot;
    }

    /** 记录观众问过的问题（去重、限量），供下次欢迎语/回复时参考。 */
    public void recordQuestion(String openid, String question) {
        if (!props.getMemory().isEnabled() || openid == null || question == null || question.isBlank()) {
            return;
        }
        ViewerProfile p = profiles.get(openid);
        if (p == null) {
            return;
        }
        int limit = Math.max(1, props.getMemory().getMaxQuestionsPerViewer());
        synchronized (p) {
            if (!p.questions.contains(question)) {
                p.questions.add(question);
                while (p.questions.size() > limit) {
                    p.questions.remove(0);
                }
            }
        }
        dirty = true;
    }

    /** 取档案（不记录访问），不存在返回 null。 */
    public ViewerProfile peek(String openid) {
        return openid == null ? null : profiles.get(openid);
    }

    /**
     * 生成给 LLM 用的观众背景描述；无历史（新观众）返回空串。
     * 例：「这位观众是第 3 次来访，上次 11-02 来过，曾问过：猫咪多大了、多少钱」
     */
    public String describeForPrompt(ViewerProfile p) {
        if (p == null || !p.isReturning || !props.getMemory().isPersonalizedWelcome()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("这位观众是第 ").append(p.visits).append(" 次来访该直播间");
        if (p.firstSeen > 0) {
            sb.append("，首次到访 ").append(formatDay(p.firstSeen));
        }
        List<String> qs = new ArrayList<>(p.questions);
        if (!qs.isEmpty()) {
            sb.append("，曾问过：").append(String.join("、", qs));
        }
        sb.append("。欢迎语要自然地体现出你记得ta（但不要生硬报数字）。");
        return sb.toString();
    }

    /**
     * 记忆文件路径。模拟模式自动切到 {@code *-sim.json}：
     * 否则 sim-1 这类测试 openid 会混进真实观众档案，「老观众」判断和统计都被污染。
     */
    private Path memoryFile() {
        String configured = props.getMemory().getFile();
        if ("simulate".equalsIgnoreCase(props.getCollector().getMode())) {
            configured = configured.replaceAll("\\.json$", "") + "-sim.json";
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private String formatDay(long epochMs) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(DAY);
    }

    /** 定期写盘（间隔见配置），避免每条弹幕都落盘。 */
    @Scheduled(fixedDelay = 10_000L)
    synchronized void flushIfNeeded() {
        if (!props.getMemory().isEnabled() || !dirty) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(5, props.getMemory().getFlushIntervalSeconds()) * 1000L;
        if (now - lastFlushAt.get() < interval) {
            return;
        }
        flush();
    }

    /** 逐个档案在锁内复制一份，序列化用副本，避免与写入线程竞争。 */
    private Map<String, ViewerProfile> snapshot() {
        Map<String, ViewerProfile> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ViewerProfile> entry : profiles.entrySet()) {
            ViewerProfile profile = entry.getValue();
            synchronized (profile) {
                copy.put(entry.getKey(), profile.copy());
            }
        }
        return copy;
    }

    /** 立即写盘（退出时兜底）。 */
    public synchronized void flush() {
        if (!props.getMemory().isEnabled()) {
            return;
        }
        evictIfNeeded();
        Path file = memoryFile();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // 先写临时文件再原子替换，避免进程被 kill 时留下半个 JSON
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // 必须序列化快照：EventWorker 可能正在 recordQuestion，
            // 直接序列化 profiles 会读 size 后 get 越界（Index out of bounds）
            Files.writeString(tmp, mapper.writeValueAsString(snapshot()), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            lastFlushAt.set(System.currentTimeMillis());
            dirty = false;
            log.debug("观众记忆已落盘 {} 条 -> {}", profiles.size(), file);
        } catch (IOException e) {
            log.warn("观众记忆落盘失败: {}", e.getMessage());
        }
    }

    /** 超出上限时按最后访问时间淘汰最旧的档案。 */
    private void evictIfNeeded() {
        int max = props.getMemory().getMaxViewers();
        if (profiles.size() <= max) {
            return;
        }
        List<Map.Entry<String, ViewerProfile>> sorted = new ArrayList<>(profiles.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().lastSeen));
        int removeCount = profiles.size() - max;
        for (int i = 0; i < removeCount && i < sorted.size(); i++) {
            profiles.remove(sorted.get(i).getKey());
        }
        log.info("观众记忆超出上限 {}，淘汰最久未访问的 {} 条", max, removeCount);
    }

    @PreDestroy
    void shutdown() {
        flush();
    }

    private final AtomicLong statVisits = new AtomicLong();
    private final AtomicLong statReturning = new AtomicLong();

    /** 运行统计，供 /memory/stats 展示。 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.getMemory().isEnabled());
        m.put("profiles", profiles.size());
        m.put("totalVisits", statVisits.get());
        m.put("returningVisits", statReturning.get());
        m.put("file", memoryFile().toString());
        return m;
    }

    /** 观众档案。字段用 public 便于 Jackson 直接读写，避免额外注解。 */
    public static class ViewerProfile {
        public String nickname = "";
        public long firstSeen;
        public long lastSeen;
        public int visits;
        /**
         * 历史提问（去重，最多 max-questions-per-viewer 条）。
         * 用写时复制列表：EventWorker 会写，定时落盘和 HTTP 观测会同时读同一份数据。
         */
        public List<String> questions = new CopyOnWriteArrayList<>();

        /** 运行期标记，不落盘：本次访问前是否已有历史。 */
        public transient boolean isReturning;

        public ViewerProfile copy() {
            ViewerProfile c = new ViewerProfile();
            c.nickname = nickname;
            c.firstSeen = firstSeen;
            c.lastSeen = lastSeen;
            c.visits = visits;
            c.questions = new ArrayList<>(questions);
            return c;
        }
    }
}
