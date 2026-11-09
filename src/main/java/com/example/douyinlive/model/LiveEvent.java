package com.example.douyinlive.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 直播间互动事件（统一中间模型）。
 *
 * <p>由 DanmakuCollector 从抖音直播间事件映射而来；
 * 保留通用字段便于后续替换采集来源。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LiveEvent {

    /** 事件类型：ENTER 进场 / COMMENT 评论 / LIKE 点赞 / GIFT 送礼 / FOLLOW 关注。 */
    private Type type = Type.COMMENT;

    /** 触发用户的 openid，服务端也会从 x-tt-openid 头兜底填充。 */
    private String openid;

    /** 昵称，用于欢迎语。 */
    private String nickname;

    /** 评论内容，type=COMMENT 时必填。 */
    private String content;

    /** 送礼/点赞等场景的数量。 */
    private Integer count;

    /** 直播间标识，用于多房间隔离（主播 roomId 或 liveRoomId）。 */
    private String roomId;

    /** 事件时间戳（毫秒），前端不传则由服务端补当前时间。 */
    private Long timestamp;

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    public enum Type {
        ENTER, COMMENT, LIKE, GIFT, FOLLOW, UNKNOWN;

        /** 大小写不敏感解析，未知值统一归到 UNKNOWN，避免抛异常打断直播流。 */
        public static Type parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            try {
                return Type.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }
}
