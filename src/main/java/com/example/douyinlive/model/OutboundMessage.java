package com.example.douyinlive.model;

/**
 * 一次处理结果的描述：要播报的文本 + 可选的语音地址。
 *
 * <p>注意：本项目只负责生成内容与音频，不向直播间发送任何消息。
 */
public class OutboundMessage {

    /** 展示形态：DANMU 玩法内飘屏 / CARD 卡片 / TOAST 轻提示 / TTS 语音播报。 */
    private Display display = Display.DANMU;

    /** 要展示/播报的文本。 */
    private String text;

    /** 回复针对的目标用户昵称，前端可做定向展示。 */
    private String toNickname;

    /** 回复来源：FAQ 命中 / LLM 生成 / WELCOME 模板。 */
    private Source source = Source.LLM;

    /** 被频控或去重丢弃时，text 为空且 suppressed=true，前端不展示。 */
    private boolean suppressed;

    /** 丢弃原因（频控/去重/空内容等），仅用于日志排查，不返回给前端。 */
    private String suppressReason;

    /** 服务端处理耗时，便于排查延迟。 */
    private long costMs;

    /**
     * 合成好的语音 URL（可空）。有值时前端应优先播报语音，文本作为字幕/兜底。
     * 内网/本机联调时是 http://127.0.0.1:8080/audio/xxx.mp3；
     * 部署到抖音云后改成 publicUrlBase 对应的容器访问地址。
     */
    private String audioUrl;

    public static OutboundMessage of(String text, Display display, Source source) {
        OutboundMessage msg = new OutboundMessage();
        msg.text = text;
        msg.display = display;
        msg.source = source;
        return msg;
    }

    public static OutboundMessage suppressed(String reason) {
        OutboundMessage msg = new OutboundMessage();
        msg.suppressed = true;
        msg.text = "";
        msg.source = Source.SUPPRESSED;
        msg.suppressReason = reason;
        return msg;
    }

    public Display getDisplay() { return display; }
    public void setDisplay(Display display) { this.display = display; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getToNickname() { return toNickname; }
    public void setToNickname(String toNickname) { this.toNickname = toNickname; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public boolean isSuppressed() { return suppressed; }
    public void setSuppressed(boolean suppressed) { this.suppressed = suppressed; }
    public String getSuppressReason() { return suppressReason; }
    public void setSuppressReason(String suppressReason) { this.suppressReason = suppressReason; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }

    public enum Display { DANMU, CARD, TOAST, TTS }

    public enum Source { FAQ, LLM, WELCOME, FALLBACK, SUPPRESSED }
}
