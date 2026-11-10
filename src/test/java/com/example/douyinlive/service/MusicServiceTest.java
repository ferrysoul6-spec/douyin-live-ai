package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

class MusicServiceTest {

    private AppProperties props;
    private MusicService music;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        music = new MusicService(props, new ObjectMapper());
    }

    @Test
    void 识别点歌指令的多种说法() {
        for (String text : new String[]{"点歌 晴天", "我想听 晴天", "来一首 晴天", "放一首 晴天", "唱一首 晴天"}) {
            assertThat(music.parse(text))
                    .as(text)
                    .contains(new MusicService.Command(MusicService.CommandType.PLAY, "晴天"));
        }
        assertThat(music.parse("点歌：晴天")).isPresent();
    }

    @Test
    void 识别切歌与歌单() {
        assertThat(music.parse("切歌")).contains(new MusicService.Command(MusicService.CommandType.NEXT, null));
        assertThat(music.parse("下一首")).isPresent();
        assertThat(music.parse("跳过这首")).isPresent();
        assertThat(music.parse("歌单")).contains(new MusicService.Command(MusicService.CommandType.LIST, null));
    }

    @Test
    void 普通弹幕不当成点歌() {
        assertThat(music.parse("多少钱")).isEmpty();
        assertThat(music.parse("能开发小程序吗")).isEmpty();
        assertThat(music.parse("点歌")).isEmpty();
        assertThat(music.parse(null)).isEmpty();
    }

    @Test
    void 没配解析服务时不入队也不假装排上() {
        MusicService.EnqueueResult result = music.enqueue("晴天", "测试观众");

        assertThat(result.ok()).isFalse();
        assertThat(result.full()).isFalse();
        assertThat(music.state()).containsEntry("current", "");
        assertThat(music.state()).containsEntry("supported", false);
    }

    @Test
    void 关闭点歌后播放器取不到歌() {
        props.getMusic().setEnabled(false);

        assertThat(music.takeNext()).isEmpty();
        assertThat(music.requestSkip()).isFalse();
    }

    @Test
    void 状态里带队列与正在播字段() {
        assertThat(music.state())
                .containsKeys("enabled", "supported", "current", "queue", "queueMax", "seq");
    }
}
