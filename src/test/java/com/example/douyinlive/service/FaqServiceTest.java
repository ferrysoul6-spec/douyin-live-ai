package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class FaqServiceTest {

    private FaqService faq;

    @BeforeEach
    void setUp() {
        faq = new FaqService(new ObjectMapper());
        faq.load();
    }

    @Test
    void 命中关键词时返回对应答案() {
        FaqService.FaqEntry hit = faq.match("这个多少钱呀");

        assertThat(hit).isNotNull();
        assertThat(hit.answer()).contains("私信");
    }

    @Test
    void 命中多条时取关键词最多的那条() {
        // 「能开发」「开发小程序」「小程序」都指向开发接单条目
        FaqService.FaqEntry hit = faq.match("能开发小程序吗");

        assertThat(hit).isNotNull();
        assertThat(hit.keywords()).contains("开发小程序");
    }

    @Test
    void 问工期命中周期条而不是接单条() {
        // 回归：「开发一个小程序要多久」里同时有「小程序」和「多久」，
        // 曾因接单条的泛词「小程序」抢票而答非所问
        FaqService.FaqEntry hit = faq.match("开发一个小程序要多久");

        assertThat(hit).isNotNull();
        assertThat(hit.question()).contains("周期");
    }

    @Test
    void 报价和工期条目不会给出具体数字() {
        assertThat(faq.match("多少钱").answer()).doesNotContain("元", "块");
        assertThat(faq.match("工期多久").answer()).doesNotContain("天");
    }

    @Test
    void 没有关键词命中时返回null() {
        assertThat(faq.match("主播今天心情不错")).isNull();
        assertThat(faq.match("   ")).isNull();
        assertThat(faq.match(null)).isNull();
    }

    @Test
    void 上下文按条目数截取且非空() {
        String context = faq.buildContext(2);

        assertThat(context).isNotBlank();
        assertThat(context.lines().count()).isEqualTo(4); // 2 条 × (问 + 答)
    }
}
