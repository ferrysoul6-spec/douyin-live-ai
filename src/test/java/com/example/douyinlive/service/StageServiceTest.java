package com.example.douyinlive.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

class StageServiceTest {

    private StageService newStage(String idleActions) {
        AppProperties props = new AppProperties();
        props.getStage().setIdleActions(idleActions);
        props.getStage().setIdleAfterSeconds(0);
        ObjectMapper mapper = new ObjectMapper();
        return new StageService(props, new MusicService(props, mapper), mapper);
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

    @Test
    void auto模式从清单装填全部动作并按帧数算时长() throws Exception {
        Path tmp = Files.createTempFile("manifest", ".json");
        Files.writeString(tmp, """
                {"fps": 24, "clips": {
                  "idle": {"frames": 241},
                  "wave": {"frames": 240},
                  "r01": {"frames": 120}
                }}""");
        try {
            AppProperties props = new AppProperties();
            props.getStage().setIdleActions("auto");
            props.getStage().setIdleActionsFile(tmp.toString());
            props.getStage().setIdleAfterSeconds(0);
            ObjectMapper mapper = new ObjectMapper();
            StageService stage = new StageService(props, new MusicService(props, mapper), mapper);

            // idle 是基础循环态，不该混进轮换池
            assertThat(this.<List<String>>field(stage, "idleActions"))
                    .containsExactlyInAnyOrder("wave", "r01");

            // 时长 = 帧数 / fps，多给 300ms：非循环动作播完会停在最后一帧
            Map<String, Integer> hold = field(stage, "holdMsByAction");
            assertThat(hold).containsEntry("wave", 10_300).containsEntry("r01", 5_300);
            assertThat(hold).doesNotContainKey("idle");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void 清单读不到时退化为只播idle() throws Exception {
        AppProperties props = new AppProperties();
        props.getStage().setIdleActions("auto");
        props.getStage().setIdleActionsFile("/nonexistent/manifest.json");
        props.getStage().setIdleAfterSeconds(0);
        ObjectMapper mapper = new ObjectMapper();
        StageService stage = new StageService(props, new MusicService(props, mapper), mapper);

        // 宁可只播待机循环，也不能让画面没动作可切
        assertThat(this.<List<String>>field(stage, "idleActions")).containsExactly("idle");

        rewindTimers(stage);
        stage.rotateIdle();
        assertThat(stage.state()).containsEntry("action", "idle");
    }

    @SuppressWarnings("unchecked")
    private <T> T field(StageService stage, String name) throws Exception {
        Field f = StageService.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(stage);
    }
}
