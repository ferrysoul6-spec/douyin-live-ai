package com.example.douyinlive.collector;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.douyinlive.model.LiveEvent;
import com.example.douyinlive.model.OutboundMessage;
import com.example.douyinlive.service.ReplyService;

/**
 * 模拟事件源：不依赖签名服务和真实直播间，本地把全链路走一遍
 * （频控 → FAQ/LLM → TTS → 播放队列），用于验证与演示。
 *
 * <p>开启方式：live-ai.collector.mode=simulate。
 * 启动时脚本会自动拉起内嵌播放器，直接就能听到 AI 的语音回复。
 *
 * <p>这里刻意同步调用 ReplyService 而不走 EventWorker：模拟器本身就是按 4s 间隔
 * 串行造事件，不需要解耦，同步反而能让日志顺序和耗时一眼可读。
 */
public class DanmakuSimulator {

    private static final Logger log = LoggerFactory.getLogger(DanmakuSimulator.class);
    private static final long INTERVAL_MS = 4000;

    /** 覆盖全部事件类型；前两条评论命中 FAQ（无 LLM Key 也能出完整回复+语音）。 */
    private static final List<LiveEvent> SCRIPT = List.of(
            event(LiveEvent.Type.ENTER, "sim-1", "爱吃糖的小美", null),
            event(LiveEvent.Type.COMMENT, "sim-1", "爱吃糖的小美", "多少钱"),
            event(LiveEvent.Type.COMMENT, "sim-2", "阿凯同学", "能开发小程序吗"),
            event(LiveEvent.Type.COMMENT, "sim-3", "路过的宝子", "开发一个小程序要多久"),
            gift("sim-1", "爱吃糖的小美", 10),
            event(LiveEvent.Type.FOLLOW, "sim-2", "阿凯同学", null),
            event(LiveEvent.Type.ENTER, "sim-4", "静静看直播", null)
    );

    private final ReplyService replyService;

    public DanmakuSimulator(ReplyService replyService) {
        this.replyService = replyService;
    }

    void run() {
        Thread t = new Thread(() -> {
            log.info("=== 模拟模式启动：{} 个事件，间隔 {}ms ===", SCRIPT.size(), INTERVAL_MS);
            for (LiveEvent event : SCRIPT) {
                OutboundMessage reply = replyService.handle(event);
                if (reply.isSuppressed()) {
                    log.info("[模拟] type={} nickname={} -> 被频控/去重: {}",
                            event.getType(), event.getNickname(), reply.getText());
                } else {
                    log.info("[模拟] {} {} -> [{}]: {}{}",
                            event.getType(),
                            event.getNickname(),
                            reply.getSource(),
                            reply.getText(),
                            reply.getAudioUrl() == null ? "" : " | 语音: " + reply.getAudioUrl());
                }
                sleep(INTERVAL_MS);
            }
            log.info("=== 模拟事件播放完毕，服务保持运行（/health、/audio、/memory 仍可用）===");
        }, "danmaku-simulator");
        t.setDaemon(true);
        t.start();
    }

    /** 连击礼物：带连击数，用来验证「感谢 X 个礼物」的文案。 */
    private static LiveEvent gift(String openid, String nickname, int count) {
        LiveEvent e = event(LiveEvent.Type.GIFT, openid, nickname, "小心心");
        e.setCount(count);
        return e;
    }

    private static LiveEvent event(LiveEvent.Type type, String openid, String nickname, String content) {
        LiveEvent e = new LiveEvent();
        e.setType(type);
        e.setOpenid(openid);
        e.setNickname(nickname);
        e.setContent(content);
        e.setRoomId("simulate-room");
        e.setTimestamp(System.currentTimeMillis());
        return e;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
