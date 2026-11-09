package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;

class RateLimiterTest {

    private AppProperties props;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        limiter = new RateLimiter(props);
    }

    @Test
    void 同一观众在去重窗口内只欢迎一次() {
        assertThat(limiter.tryWelcome("room", "u1")).isTrue();
        assertThat(limiter.tryWelcome("room", "u1")).isFalse();
    }

    @Test
    void 不同观众互不影响() {
        assertThat(limiter.tryWelcome("room", "u1")).isTrue();
        assertThat(limiter.tryWelcome("room", "u2")).isTrue();
        assertThat(limiter.tryWelcome("other-room", "u1")).isTrue();
    }

    @Test
    void 同一观众连续提问会被冷却拦下() {
        assertThat(limiter.tryReply("room", "u1")).isTrue();
        assertThat(limiter.tryReply("room", "u1")).isFalse();
    }

    @Test
    void 欢迎语总量用满后不再发言() {
        int limit = props.getThrottle().getWelcomePerMinute();

        for (int i = 0; i < limit; i++) {
            assertThat(limiter.tryBroadcastWelcome()).as("第 %s 条", i + 1).isTrue();
        }
        assertThat(limiter.tryBroadcastWelcome()).isFalse();
    }

    @Test
    void 评论回复额度用满后拒绝() {
        int limit = props.getThrottle().getReplyLlmPerMinute();

        for (int i = 0; i < limit; i++) {
            assertThat(limiter.tryAcquireLlm(false)).as("第 %s 次", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquireLlm(false)).isFalse();
    }

    @Test
    void 欢迎额度不影响评论额度() {
        int welcomeLimit = props.getThrottle().getWelcomeLlmPerMinute();
        for (int i = 0; i < welcomeLimit; i++) {
            assertThat(limiter.tryAcquireLlm(true)).isTrue();
        }
        assertThat(limiter.tryAcquireLlm(true)).isFalse();
        // 欢迎额度耗尽时，评论仍能正常拿到额度
        assertThat(limiter.tryAcquireLlm(false)).isTrue();
    }

    @Test
    void 两类合计超过总闸后拒绝() {
        int welcome = props.getThrottle().getWelcomeLlmPerMinute();
        int reply = props.getThrottle().getReplyLlmPerMinute();
        int total = props.getThrottle().getGlobalLlmPerMinute();

        for (int i = 0; i < welcome; i++) {
            limiter.tryAcquireLlm(true);
        }
        for (int i = 0; i < reply; i++) {
            limiter.tryAcquireLlm(false);
        }
        // 总量已到总闸（10 + 30 = 40），两类都拿不到
        assertThat(welcome + reply).isEqualTo(total);
        assertThat(limiter.tryAcquireLlm(true)).isFalse();
        assertThat(limiter.tryAcquireLlm(false)).isFalse();
    }
}
