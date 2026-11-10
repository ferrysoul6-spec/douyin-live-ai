package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

class StageServiceTest {

    private StageService newStage(String idleActions) {
        AppProperties props = new AppProperties();
        props.getStage().setIdleActions(idleActions);
        props.getStage().setIdleAfterSeconds(0);
        return new StageService(props, new MusicService(props, new ObjectMapper()));
    }

    /** 把「上次活动」「上次轮换」拨到很久以前，跳过 18s / 12s 的节流。 */
    private void rewindTimers(StageService stage) throws Exception {
        for (String name : new String[]{"lastActivityAt", "lastRotateAt"}) {
            Field f = StageService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(stage, 0L);
        }
    }

    private String awaitAction(StageService stage, String expect, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String action = String.valueOf(stage.state().get("action"));
        while (!expect.equals(action) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            action = String.valueOf(stage.state().get("action"));
        }
        return action;
    }

    @Test
    void 空闲轮换抽到一次性动作后必须自己回idle() throws Exception {
        StageService stage = newStage("tea");
        rewindTimers(stage);

        stage.rotateIdle();
        assertThat(stage.state()).containsEntry("action", "tea");

        // 不回 idle 的话，rotateIdle 里的 !"idle".equals(action) 会把轮换永久锁死，
        // 画面就定格在 tea 的最后一帧再也不动
        assertThat(awaitAction(stage, "idle", 6_000)).isEqualTo("idle");
    }

    @Test
    void 回到idle之后还能继续轮换() throws Exception {
        StageService stage = newStage("tea");
        rewindTimers(stage);
        stage.rotateIdle();
        assertThat(awaitAction(stage, "idle", 6_000)).isEqualTo("idle");

        long seqBefore = ((Number) stage.state().get("seq")).longValue();
        rewindTimers(stage);
        stage.rotateIdle();

        assertThat(((Number) stage.state().get("seq")).longValue()).isGreaterThan(seqBefore);
    }

    @Test
    void 抽到idle本身只重放循环() throws Exception {
        StageService stage = newStage("idle");
        rewindTimers(stage);

        stage.rotateIdle();

        assertThat(stage.state()).containsEntry("action", "idle");
        assertThat(((Number) stage.state().get("seq")).longValue()).isEqualTo(1);
    }
}
