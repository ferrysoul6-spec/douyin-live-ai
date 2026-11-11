package com.example.douyinlive.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.douyinlive.model.LiveEvent;
import com.example.douyinlive.service.EventWorker;

/**
 * 弹幕中继入口：浏览器控制台脚本（{@code live-assets/relay/douyin-relay.js}）
 * 把直播间聊天区里的事件 POST 过来，绕开第三方签名服务。
 *
 * <p>只做「归一化 + 投递」：真正的回复编排仍走 {@link EventWorker}，
 * 所以中继和 SDK 采集共用同一套频控、观众记忆、TTS 与舞台动作。
 *
 * <p>注意：中继和 SDK 采集同时开着会重复处理同一条弹幕，
 * 用中继时建议把 {@code collector.mode} 设成 {@code off}。
 */
@RestController
public class DanmuRelayController {

    private static final Logger log = LoggerFactory.getLogger(DanmuRelayController.class);

    private final EventWorker eventWorker;

    public DanmuRelayController(EventWorker eventWorker) {
        this.eventWorker = eventWorker;
    }

    /**
     * 接收一条中继事件。
     *
     * <p>兼容两种载荷：本项目的 {@code {type,username,content}}，
     * 以及 DouyinBarrageGrab 的 {@code {Type, Data:"{...}", User:"{...}"}}。
     */
    @PostMapping("/danmu")
    @CrossOrigin(origins = {"https://live.douyin.com", "https://www.douyin.com",
            "https://webcast.amemv.com"})
    public Map<String, Object> relay(@RequestBody Map<String, Object> body) {
        LiveEvent event = toEvent(body);
        boolean accepted = eventWorker.submit(event);
        if (!accepted) {
            log.warn("中继事件未入队（队列满或已停止）: type={} nickname={}",
                    event.getType(), event.getNickname());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", accepted);
        resp.put("type", event.getType().name());
        resp.put("nickname", event.getNickname());
        return resp;
    }

    /** 把各种形状的载荷归一化成 LiveEvent。包级可见，方便单测直接打。 */
    static LiveEvent toEvent(Map<String, Object> body) {
        Map<String, Object> data = nested(body, "Data", "data");
        Map<String, Object> user = nested(data, "User", "user");

        String rawType = firstText(body, "type", "Type", "event", "msgType", "MsgType");
        String nickname = firstText(user, "Nickname", "nickname");
        if (isBlank(nickname)) {
            nickname = firstText(data, "Nickname", "nickname", "username", "userName");
        }
        if (isBlank(nickname)) {
            nickname = firstText(body, "username", "nickname", "user");
        }
        String content = firstText(data, "Content", "content", "Message", "message", "text");
        if (isBlank(content)) {
            content = firstText(body, "content", "text", "message");
        }
        // 点赞/礼物的数量：点赞要靠它累计，礼物要靠它说「N 个」
        Integer count = parseCount(firstText(data, "count", "Count", "repeatCount", "repeat_count", "num"));
        if (count == null) {
            count = parseCount(firstText(body, "count", "Count", "repeatCount", "repeat_count", "num"));
        }

        LiveEvent event = new LiveEvent();
        event.setType(mapType(rawType));
        event.setNickname(isBlank(nickname) ? "热心观众" : nickname.trim());
        event.setContent(content == null ? "" : content.trim());
        event.setCount(count);
        event.setTimestamp(System.currentTimeMillis());
        return event;
    }

    private static Integer parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 中继脚本按名字传，dy-barrage-grab 按数字传（1 弹幕 / 2 点赞 / 3 进场 / 4 关注 / 5 礼物 / 7 粉丝团）。 */
    private static LiveEvent.Type mapType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase();
        return switch (t) {
            case "3", "member", "join", "enter", "进场", "进入直播间", "进入" -> LiveEvent.Type.ENTER;
            case "4", "social", "follow", "subscribe", "关注", "关注主播" -> LiveEvent.Type.FOLLOW;
            case "5", "gift", "present", "送礼", "礼物" -> LiveEvent.Type.GIFT;
            case "2", "like", "点赞" -> LiveEvent.Type.LIKE;
            // 粉丝团没有独立事件类型，按关注处理（都是「进来涨粉」的意思）
            case "7", "fansclub", "fans_club", "fans", "粉丝团" -> LiveEvent.Type.FOLLOW;
            default -> LiveEvent.Type.COMMENT;
        };
    }

    /** 取一层嵌套对象；字符串形式的 JSON 也尝试解析（dy-barrage-grab 就是这种）。 */
    private static Map<String, Object> nested(Map<String, Object> source, String... keys) {
        if (source == null) {
            return Map.of();
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof Map<?, ?> map) {
                return toStringMap(map);
            }
            if (value instanceof String text && text.trim().startsWith("{")) {
                try {
                    return toStringMap(new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(text, Map.class));
                } catch (Exception ignored) {
                    // 不是合法 JSON 就当没有，继续用外层字段
                }
            }
        }
        return Map.of();
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String firstText(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
