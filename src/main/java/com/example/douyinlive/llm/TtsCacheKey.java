package com.example.douyinlive.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * TTS 音频缓存键：按「音色|文本」生成 32 位十六进制串，两个引擎共用同一套规则。
 *
 * <p>用 SHA-256 前 16 字节（32 个 hex 字符）而非 {@code hashCode()}：
 * 后者 32 位碰撞概率在几万条话术下已不可忽略，且不同 JVM 间不稳定。
 */
final class TtsCacheKey {

    private TtsCacheKey() {
    }

    static String of(String voice, String text) {
        String raw = voice + "|" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // JDK 一定带 SHA-256；这里的兜底只为保持与旧缓存文件的兼容
            return Integer.toHexString(raw.hashCode());
        }
    }
}
