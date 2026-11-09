package com.example.douyinlive.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * FAQ 知识库：从 resources/faq.json 加载，按关键词命中。
 *
 * <p>两层用途：
 * 1. 命中且配置 faq-shortcut=true 时直接返回标准答案，省 token、零延迟、答案可控；
 * 2. 未命中时把最相关的几条作为上下文喂给 LLM，避免它编造商品信息。
 */
@Service
public class FaqService {

    private static final Logger log = LoggerFactory.getLogger(FaqService.class);
    private static final String FAQ_PATH = "faq.json";

    private final ObjectMapper mapper;
    private volatile List<FaqEntry> entries = Collections.emptyList();

    public FaqService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FAQ_PATH)) {
            if (in == null) {
                log.warn("未找到 {}，FAQ 功能降级为空知识库", FAQ_PATH);
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<FaqEntry> parsed = mapper.readValue(json, new TypeReference<>() {});
            this.entries = Collections.unmodifiableList(new ArrayList<>(parsed));
            log.info("已加载 FAQ {} 条", entries.size());
        } catch (Exception e) {
            log.error("加载 {} 失败，FAQ 降级为空知识库", FAQ_PATH, e);
        }
    }

    /** 返回关键词命中最多的一条；没有命中返回 null。 */
    public FaqEntry match(String question) {
        if (question == null || question.isBlank() || entries.isEmpty()) {
            return null;
        }
        String q = question.toLowerCase();
        FaqEntry best = null;
        int bestHits = 0;
        for (FaqEntry e : entries) {
            int hits = 0;
            for (String kw : e.keywords()) {
                if (kw != null && !kw.isBlank() && q.contains(kw.toLowerCase())) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = e;
            }
        }
        return best;
    }

    /** 取前 N 条作为 LLM 上下文，条目少时全量返回。 */
    public String buildContext(int limit) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, entries.size());
        for (int i = 0; i < n; i++) {
            FaqEntry e = entries.get(i);
            sb.append("- 问：").append(e.question()).append('\n')
              .append("  答：").append(e.answer()).append('\n');
        }
        return sb.toString();
    }

    /**
     * FAQ 条目。用 record 即可，Jackson 支持 record 反序列化。
     *
     * @param question 标准问法
     * @param answer   标准答案（会直接展示，措辞要像主播口吻）
     * @param keywords 命中关键词，任一命中即算匹配
     */
    public record FaqEntry(String question, String answer, List<String> keywords) {

        public FaqEntry {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }
}
