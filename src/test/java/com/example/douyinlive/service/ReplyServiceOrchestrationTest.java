package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;
import com.example.douyinlive.llm.LlmClient;
import com.example.douyinlive.llm.LlmException;
import com.example.douyinlive.llm.TtsAsyncService;
import com.example.douyinlive.model.LiveEvent;
import com.example.douyinlive.model.OutboundMessage;

/**
 * 编排层的分支与降级：每个分支只调该调的东西，失败一定落到兜底而不是抛异常。
 */
class ReplyServiceOrchestrationTest {

    private AppProperties props;
    private FaqService faq;
    private RateLimiter limiter;
    private LlmClient llm;
    private TtsAsyncService tts;
    private ViewerMemoryService memory;
    private MusicService music;
    private StageService stage;
    private ReplyService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        faq = mock(FaqService.class);
        limiter = mock(RateLimiter.class);
        llm = mock(LlmClient.class);
        tts = mock(TtsAsyncService.class);
        memory = mock(ViewerMemoryService.class);
        music = mock(MusicService.class);
        stage = mock(StageService.class);
        service = new ReplyService(props, faq, limiter, llm, tts, memory, music, stage);

        when(tts.cachedOrDispatch(anyString(), any())).thenReturn(Optional.empty());
        // 真实实现遇到 null 档案会返回空串，mock 默认返回 null 会 NPE
        when(memory.describeForPrompt(any())).thenReturn("");
        when(faq.buildContext(5)).thenReturn("");
        when(limiter.tryReply(any(), any())).thenReturn(true);
        when(limiter.tryWelcome(any(), any())).thenReturn(true);
        when(limiter.tryBroadcastWelcome()).thenReturn(true);
        when(limiter.tryAcquireLlm(false)).thenReturn(true);
        when(limiter.tryAcquireLlm(true)).thenReturn(true);
    }

    @Test
    void FAQ命中时直接返回_不消耗LLM() {
        when(faq.match("多少钱")).thenReturn(new FaqService.FaqEntry(
                "开发怎么收费", "价格看具体功能，私信主播他给报价。", List.of("多少钱")));

        OutboundMessage msg = service.handle(comment("多少钱"));

        assertThat(msg.getSource()).isEqualTo(OutboundMessage.Source.FAQ);
        assertThat(msg.getText()).contains("私信");
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void 冷却中的评论被丢弃且原因可见() {
        when(limiter.tryReply(any(), any())).thenReturn(false);

        OutboundMessage msg = service.handle(comment("多少钱"));

        assertThat(msg.isSuppressed()).isTrue();
        assertThat(msg.getSuppressReason()).isEqualTo("用户回复冷却中");
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void LLM失败时落到话术池而不是抛异常() {
        when(faq.match(anyString())).thenReturn(null);
        when(llm.chat(anyString(), anyString())).thenThrow(new LlmException("方舟 HTTP 500"));

        OutboundMessage msg = service.handle(comment("你们小程序能做吗"));

        assertThat(msg.isSuppressed()).isFalse();
        assertThat(msg.getSource()).isEqualTo(OutboundMessage.Source.FALLBACK);
        assertThat(props.getPersona().getFallbackReplies()).contains(msg.getText());
    }

    @Test
    void LLM额度耗尽时也落到话术池() {
        when(faq.match(anyString())).thenReturn(null);
        when(limiter.tryAcquireLlm(false)).thenReturn(false);

        OutboundMessage msg = service.handle(comment("能帮我写代码吗"));

        assertThat(msg.getSource()).isEqualTo(OutboundMessage.Source.FALLBACK);
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void 欢迎语去重后不再发言() {
        when(limiter.tryWelcome(any(), any())).thenReturn(false);

        OutboundMessage msg = service.handle(event(LiveEvent.Type.ENTER, null, null));

        assertThat(msg.isSuppressed()).isTrue();
        assertThat(msg.getSuppressReason()).isEqualTo("欢迎语去重");
    }

    @Test
    void 欢迎语每分钟上限生效_即使还有LLM额度() {
        when(limiter.tryBroadcastWelcome()).thenReturn(false);

        OutboundMessage msg = service.handle(event(LiveEvent.Type.ENTER, null, null));

        assertThat(msg.isSuppressed()).isTrue();
        assertThat(msg.getSuppressReason()).isEqualTo("欢迎语每分钟上限");
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void 欢迎语没有LLM额度时用模板保底() {
        when(limiter.tryAcquireLlm(true)).thenReturn(false);

        OutboundMessage msg = service.handle(event(LiveEvent.Type.ENTER, null, null));

        assertThat(msg.getSource()).isEqualTo(OutboundMessage.Source.WELCOME);
        assertThat(msg.getText()).contains("欢迎");
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void 礼物文案带连击数量() {
        LiveEvent gift = event(LiveEvent.Type.GIFT, "小心心", 10);

        OutboundMessage msg = service.handle(gift);

        assertThat(msg.getText()).contains("10 个小心心");
    }

    @Test
    void 点赞攒够数量才播一次() {
        // 阈值默认 5：前 4 个赞静默累计，第 5 个才播
        for (int i = 0; i < 4; i++) {
            assertThat(service.handle(event(LiveEvent.Type.LIKE, null, 1)).isSuppressed()).isTrue();
        }

        OutboundMessage msg = service.handle(event(LiveEvent.Type.LIKE, null, 1));

        assertThat(msg.isSuppressed()).isFalse();
        assertThat(msg.getText()).contains("5 个赞");
        assertThat(msg.getSource()).isEqualTo(OutboundMessage.Source.WELCOME);
    }

    @Test
    void 点赞感谢冷却期内不重复播报() {
        props.getThrottle().setLikeThankMinCount(1);
        assertThat(service.handle(event(LiveEvent.Type.LIKE, null, 1)).isSuppressed()).isFalse();

        OutboundMessage second = service.handle(event(LiveEvent.Type.LIKE, null, 1));

        // 冷却中继续累计，下次一起说——但不能每条赞都播一遍
        assertThat(second.isSuppressed()).isTrue();
        assertThat(second.getSuppressReason()).contains("冷却");
    }

    @Test
    void 点歌指令不走FAQ和LLM() {
        when(music.parse("点歌 晴天")).thenReturn(java.util.Optional.of(
                new MusicService.Command(MusicService.CommandType.PLAY, "晴天")));
        when(music.enqueue("晴天", "测试观众")).thenReturn(
                new MusicService.EnqueueResult(true, false, "晴天 - 周杰伦"));
        when(music.state()).thenReturn(java.util.Map.of("current", ""));

        OutboundMessage msg = service.handle(comment("点歌 晴天"));

        assertThat(msg.getSource()).isEqualTo(OutboundMessage.Source.MUSIC);
        assertThat(msg.getText()).contains("晴天 - 周杰伦");
        verify(llm, never()).chat(anyString(), anyString());
        verify(faq, never()).match(anyString());
    }

    @Test
    void 点歌没播放源时明确告知() {
        when(music.parse("点歌 冷门歌")).thenReturn(java.util.Optional.of(
                new MusicService.Command(MusicService.CommandType.PLAY, "冷门歌")));
        when(music.enqueue("冷门歌", "测试观众")).thenReturn(
                new MusicService.EnqueueResult(false, false, "冷门歌"));

        OutboundMessage msg = service.handle(comment("点歌 冷门歌"));

        assertThat(msg.getText()).contains("没有播放源");
    }

    private LiveEvent comment(String content) {
        return event(LiveEvent.Type.COMMENT, content, null);
    }

    private LiveEvent event(LiveEvent.Type type, String content, Integer count) {
        LiveEvent e = new LiveEvent();
        e.setType(type);
        e.setOpenid("u1");
        e.setNickname("测试观众");
        e.setContent(content);
        e.setCount(count);
        e.setRoomId("room");
        return e;
    }
}
