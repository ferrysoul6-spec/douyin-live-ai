package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplyServiceTextTest {

    @Test
    void 多个空白压成单空格并去首尾() {
        assertThat(ReplyService.truncate("  你好   宝子 \n 今天  ", 30)).isEqualTo("你好 宝子 今天");
    }

    @Test
    void 超长文本按字数截断() {
        String text = "一".repeat(50);

        assertThat(ReplyService.truncate(text, 10)).hasSize(10);
    }

    @Test
    void 不切断emoji() {
        String emoji = "😀";

        String out = ReplyService.truncate(emoji.repeat(5), 2);

        assertThat(out.codePointCount(0, out.length())).isEqualTo(2);
        assertThat(out).isEqualTo(emoji.repeat(2));
    }

    @Test
    void 截断优先落在句末标点上() {
        String out = ReplyService.truncate("今天天气不错。后面还有很多内容", 8);

        assertThat(out).isEqualTo("今天天气不错。");
    }

    @Test
    void 只有逗号时去掉逗号不留残句() {
        String out = ReplyService.truncate("夏目摇曳又来啦，咕噜咕噜，今天想聊点什么呀", 17);

        assertThat(out).isEqualTo("夏目摇曳又来啦，咕噜咕噜");
    }

    @Test
    void 没有标点时按字数截断() {
        assertThat(ReplyService.truncate("一".repeat(50), 10)).isEqualTo("一".repeat(10));
    }

    @Test
    void 未超长时原样返回() {
        assertThat(ReplyService.truncate("欢迎宝子", 30)).isEqualTo("欢迎宝子");
        assertThat(ReplyService.truncate(null, 30)).isEmpty();
    }
}
