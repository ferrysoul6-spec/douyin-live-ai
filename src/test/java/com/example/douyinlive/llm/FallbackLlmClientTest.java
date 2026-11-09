package com.example.douyinlive.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.douyinlive.config.AppProperties;

/**
 * 模型级兜底：主模型失败换备用模型重试一次，两个都失败才抛给业务层。
 */
class FallbackLlmClientTest {

    private AppProperties props;
    private ArkLlmClient client;
    private FallbackLlmClient fallback;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        // 备用模型默认值在 application.yml 里，单测不读 yml，需显式设置
        props.getLlm().setFallbackModel("doubao-seed-2-1-turbo-260628");
        client = mock(ArkLlmClient.class);
        fallback = new FallbackLlmClient(props, client);
    }

    @Test
    void 主模型失败时落回备用模型() {
        String main = props.getLlm().getModel();
        String backup = props.getLlm().getFallbackModel();
        when(client.chatWithModel(eq(main), anyInt(), anyString(), anyString()))
                .thenThrow(new LlmException("方舟 HTTP 404"));
        when(client.chatWithModel(eq(backup), anyInt(), anyString(), anyString()))
                .thenReturn("备用模型回复");

        assertThat(fallback.chat("system", "user")).isEqualTo("备用模型回复");
        verify(client).chatWithModel(eq(backup), anyInt(), anyString(), anyString());
    }

    @Test
    void 主模型成功时不调备用模型() {
        when(client.chatWithModel(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("主模型回复");

        assertThat(fallback.chat("system", "user")).isEqualTo("主模型回复");
        verify(client, never()).chatWithModel(eq(props.getLlm().getFallbackModel()),
                anyInt(), anyString(), anyString());
    }

    @Test
    void 两个模型都失败时把主因挂在异常上() {
        when(client.chatWithModel(eq(props.getLlm().getModel()), anyInt(), anyString(), anyString()))
                .thenThrow(new LlmException("主模型 500"));
        when(client.chatWithModel(eq(props.getLlm().getFallbackModel()), anyInt(), anyString(), anyString()))
                .thenThrow(new LlmException("备用模型 500"));

        assertThatThrownBy(() -> fallback.chat("system", "user"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }

    @Test
    void 两次抛同一个异常实例时不会自抑制崩溃() {
        // addSuppressed(自身) 会抛 IllegalArgumentException，绕过 catch (LlmException)
        LlmException same = new LlmException("同一个实例");
        when(client.chatWithModel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(same);

        assertThatThrownBy(() -> fallback.chat("system", "user"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
    }

    @Test
    void 关闭兜底时只调一次主模型() {
        props.getLlm().setFallbackEnabled(false);
        when(client.chatWithModel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new LlmException("方舟 HTTP 500"));

        assertThatThrownBy(() -> fallback.chat("system", "user"))
                .isInstanceOf(LlmException.class);
        verify(client, never()).chatWithModel(eq(props.getLlm().getFallbackModel()),
                anyInt(), anyString(), anyString());
    }

    @Test
    void 备用模型与主模型同名时不重复调用() {
        props.getLlm().setFallbackModel(props.getLlm().getModel().toUpperCase());
        when(client.chatWithModel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new LlmException("方舟 HTTP 500"));

        assertThatThrownBy(() -> fallback.chat("system", "user"))
                .isInstanceOf(LlmException.class);
    }
}
