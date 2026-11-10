package com.example.douyinlive.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.douyinlive.model.LiveEvent;

class DanmuRelayControllerTest {

    private Map<String, Object> body(String type, String username, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("username", username);
        m.put("content", content);
        return m;
    }

    @Test
    void 中继脚本的事件名都认得() {
        assertThat(DanmuRelayController.toEvent(body("join", "阿强", "进入了直播间")).getType())
                .isEqualTo(LiveEvent.Type.ENTER);
        assertThat(DanmuRelayController.toEvent(body("follow", "阿强", "关注了主播")).getType())
                .isEqualTo(LiveEvent.Type.FOLLOW);
        assertThat(DanmuRelayController.toEvent(body("gift", "阿强", "送出了 小心心")).getType())
                .isEqualTo(LiveEvent.Type.GIFT);
        assertThat(DanmuRelayController.toEvent(body("like", "阿强", "点赞")).getType())
                .isEqualTo(LiveEvent.Type.LIKE);
        assertThat(DanmuRelayController.toEvent(body("chat", "阿强", "你好")).getType())
                .isEqualTo(LiveEvent.Type.COMMENT);
        // 粉丝团没有独立事件类型，按关注处理
        assertThat(DanmuRelayController.toEvent(body("fansclub", "阿强", "加入粉丝团")).getType())
                .isEqualTo(LiveEvent.Type.FOLLOW);
    }

    @Test
    void 兼容抖音抓包工具的数字类型与嵌套字符串() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("Type", 1);
        raw.put("Data", "{\"Content\":\"主播好\",\"user\":{\"Nickname\":\"老王\"}}");
        LiveEvent event = DanmuRelayController.toEvent(raw);
        assertThat(event.getType()).isEqualTo(LiveEvent.Type.COMMENT);
        assertThat(event.getNickname()).isEqualTo("老王");
        assertThat(event.getContent()).isEqualTo("主播好");
    }

    @Test
    void 缺昵称时给个兜底叫法() {
        LiveEvent event = DanmuRelayController.toEvent(body("chat", null, "在吗"));
        assertThat(event.getNickname()).isEqualTo("热心观众");
        assertThat(event.getContent()).isEqualTo("在吗");
    }
}
