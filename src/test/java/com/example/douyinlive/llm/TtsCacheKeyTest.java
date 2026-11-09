package com.example.douyinlive.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TtsCacheKeyTest {

    @Test
    void 同一音色同一文本稳定同键() {
        assertThat(TtsCacheKey.of("zh_female_vv_uranus_bigtts", "欢迎宝子"))
                .isEqualTo(TtsCacheKey.of("zh_female_vv_uranus_bigtts", "欢迎宝子"));
    }

    @Test
    void 文本不同则键不同() {
        assertThat(TtsCacheKey.of("v1", "第一句")).isNotEqualTo(TtsCacheKey.of("v1", "第二句"));
    }

    @Test
    void 音色不同则键不同() {
        assertThat(TtsCacheKey.of("v1", "欢迎宝子")).isNotEqualTo(TtsCacheKey.of("v2", "欢迎宝子"));
    }

    @Test
    void 键是32位十六进制() {
        assertThat(TtsCacheKey.of("v1", "欢迎宝子")).matches("[0-9a-f]{32}");
    }

    @Test
    void 与旧实现结果一致_旧音频缓存不会失效() {
        // SHA-256("v1|欢迎宝子") 前 32 个 hex 字符，重构前也是这个值
        assertThat(TtsCacheKey.of("v1", "欢迎宝子")).isEqualTo("e6ab5a6f2719face6fff024aa46f89eb");
    }
}
