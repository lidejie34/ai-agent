package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.exception.ChatNotConfiguredException;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.ModelCallException;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.ChatStreamResult;
import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2/T4/T5（控制器侧）：Web 切片测试。{@link ChatService} 被 mock，
 * 只验证协议适配：同步 200/400/503/502 与 SSE 分段/done/error 事件。
 */
@WebMvcTest(ChatController.class)
@Import(FastJsonWebConfig.class) // 迭代3：切片显式启用 fastjson2，与生产转换器链一致（实证 6.4）
class ChatControllerTest {

    private static final String CHAT_URL = "/api/chat";
    private static final String STREAM_URL = "/api/chat/stream";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    // ---------- T2 同步 ----------

    @Test
    void sync_200_returnsReplyAndModel() throws Exception {
        when(chatService.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("你好，我是方舟助手。", "test-model"));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("你好，我是方舟助手。"))
                .andExpect(jsonPath("$.model").value("test-model"));
    }

    @Test
    void sync_blankMessage_400() throws Exception {
        when(chatService.chat(argThat(r -> r == null || r.message() == null || r.message().isBlank())))
                .thenThrow(new InvalidChatRequestException("message 不能为空"));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("message 不能为空"));
    }

    @Test
    void sync_missingMessageField_400() throws Exception {
        when(chatService.chat(any(ChatRequest.class)))
                .thenThrow(new InvalidChatRequestException("message 不能为空"));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"history":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void sync_illegalHistoryRole_400() throws Exception {
        when(chatService.chat(any(ChatRequest.class)))
                .thenThrow(new InvalidChatRequestException("history 中 role 仅支持 user 或 assistant，收到: system"));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好","history":[{"role":"system","content":"你是一个助手"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void sync_malformedJson_400() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not a valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void sync_missingApiKey_503() throws Exception {
        when(chatService.chat(any(ChatRequest.class)))
                .thenThrow(new ChatNotConfiguredException(
                        "未检测到 ARK_API_KEY，请通过环境变量或 application-local.yml 配置后重启。"));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ARK_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void sync_modelCallFailed_502_andNoStackLeak() throws Exception {
        when(chatService.chat(any(ChatRequest.class)))
                .thenThrow(new ModelCallException("模型调用失败，请稍后重试或检查 API Key 与模型配置。",
                        new RuntimeException("boom-internal-secret")));

        String body = mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_CALL_FAILED"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("boom-internal-secret")
                .doesNotContain("Exception")
                .doesNotContain("at ");
    }

    // ---------- T4 流式 SSE ----------

    @Test
    void stream_chunksAndDone_emitsMessageEvents() throws Exception {
        // 迭代3：chatStream 返回 ChatStreamResult(sessionId, chunks)；无状态 stub sessionId=null
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, Flux.just("你", "好")));

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"说你好"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SSE 按规范固定 UTF-8 编码，MockMvc 需显式以 UTF-8 读取
        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:message")
                .contains("\"content\":\"你\"")
                .contains("\"content\":\"好\"")
                .contains("event:done")
                .contains("[DONE]");
    }

    @Test
    void stream_fluxError_emitsErrorEventAndCompletes() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenReturn(new ChatStreamResult(null, Flux.error(new ModelCallException(
                        "模型流式调用失败，请稍后重试。", new RuntimeException("stream-boom")))));

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"说你好"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:error")
                .contains("MODEL_CALL_FAILED")
                .doesNotContain("stream-boom");
    }

    @Test
    void stream_missingApiKey_emitsErrorEvent() throws Exception {
        when(chatService.chatStream(any(ChatRequest.class)))
                .thenThrow(new ChatNotConfiguredException(
                        "未检测到 ARK_API_KEY，请通过环境变量或 application-local.yml 配置后重启。"));

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:error")
                .contains("ARK_NOT_CONFIGURED");
    }
}
