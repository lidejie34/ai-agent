package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.dto.session.SessionDeleteResult;
import com.dj.ai.agentchat.dto.session.SessionMessageView;
import com.dj.ai.agentchat.dto.session.SessionSummary;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.exception.MemoryUnavailableException;
import com.dj.ai.agentchat.exception.SessionNotFoundException;
import com.dj.ai.agentchat.service.ChatService;
import com.dj.ai.agentchat.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T4：{@link SessionController} Web 切片测试——{@link SessionService} 被 mock，
 * 只验证协议适配：四端点 200 形态、fastjson2 null 省略、400/404/503/开关关闭 400 全分支、
 * 错误体 {@code {code,message,timestamp}} 无堆栈、时间字段线格式（R11 钉死）。
 */
@WebMvcTest(SessionController.class)
@Import(FastJsonWebConfig.class)
class SessionControllerTest {

    private static final String SESSIONS = "/api/sessions";
    private static final String SID = "123e4567-e89b-12d3-a456-426614174000";
    // yyyy-MM-dd 后接 'T' 或空格，再接 HH:mm:ss（fastjson2 实际线格式；前端 dayjs 宽解析两种）
    private static final Pattern TS_PATTERN =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionService sessionService;

    // ---------- GET /api/sessions ----------

    @Test
    void list_200_arrayOrderedWithPreview() throws Exception {
        LocalDateTime updated = LocalDateTime.of(2026, 9, 3, 14, 30, 0);
        when(sessionService.listSessions()).thenReturn(List.of(
                new SessionSummary(SID, "标题A",
                        LocalDateTime.of(2026, 9, 1, 10, 0, 0), updated,
                        "assistant", "预览内容…")));

        mockMvc.perform(get(SESSIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value(SID))
                .andExpect(jsonPath("$[0].title").value("标题A"))
                .andExpect(jsonPath("$[0].previewRole").value("assistant"))
                .andExpect(jsonPath("$[0].previewText").value("预览内容…"))
                .andExpect(jsonPath("$[0].updatedAt").exists());
    }

    @Test
    void list_nullTitleAndPreview_omittedByFastjson() throws Exception {
        // 空会话：title/preview 均为 null → fastjson2 默认省略键（AC-6）
        when(sessionService.listSessions()).thenReturn(List.of(
                new SessionSummary(SID, null,
                        LocalDateTime.of(2026, 9, 3, 12, 0, 0),
                        LocalDateTime.of(2026, 9, 3, 12, 0, 0),
                        null, null)));

        String body = mockMvc.perform(get(SESSIONS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("\"title\"")
                .doesNotContain("\"previewRole\"")
                .doesNotContain("\"previewText\"");
    }

    @Test
    void list_empty_returnsEmptyArray() throws Exception {
        when(sessionService.listSessions()).thenReturn(List.of());

        mockMvc.perform(get(SESSIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void list_memoryDisabled_400() throws Exception {
        when(sessionService.listSessions())
                .thenThrow(new InvalidChatRequestException(ChatService.MEMORY_DISABLED_MESSAGE));

        mockMvc.perform(get(SESSIONS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(ChatService.MEMORY_DISABLED_MESSAGE));
    }

    @Test
    void list_dbDown_503_errorBodyNoStack() throws Exception {
        when(sessionService.listSessions())
                .thenThrow(new MemoryUnavailableException(
                        "会话服务暂不可用，请稍后重试",
                        new RuntimeException("java.net.ConnectException: Connection refused 127.0.0.1:13306")));

        String body = mockMvc.perform(get(SESSIONS))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEMORY_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("Connection refused").doesNotContain("13306")
                .doesNotContain("Exception").doesNotContain("at ");
    }

    // ---------- GET /api/sessions/{id}/messages ----------

    @Test
    void messages_200_ascendingFullText() throws Exception {
        when(sessionService.listMessages(SID)).thenReturn(List.of(
                new SessionMessageView("user", "你好", LocalDateTime.of(2026, 9, 3, 14, 0, 0)),
                new SessionMessageView("assistant", "你好呀".repeat(50), LocalDateTime.of(2026, 9, 3, 14, 0, 1))));

        mockMvc.perform(get(SESSIONS + "/" + SID + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("你好"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("你好呀".repeat(50)))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    void messages_illegalUuid_400() throws Exception {
        when(sessionService.listMessages(any()))
                .thenThrow(new InvalidChatRequestException("sessionId 必须为服务端签发的 36 位会话 ID"));

        mockMvc.perform(get(SESSIONS + "/not-a-uuid/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void messages_notFound_404() throws Exception {
        when(sessionService.listMessages(SID))
                .thenThrow(new SessionNotFoundException("会话不存在或已被删除"));

        mockMvc.perform(get(SESSIONS + "/" + SID + "/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    // ---------- DELETE /api/sessions/{id} ----------

    @Test
    void delete_200_deletedTrue() throws Exception {
        when(sessionService.deleteSession(SID)).thenReturn(SessionDeleteResult.OK);

        mockMvc.perform(delete(SESSIONS + "/" + SID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void delete_repeatOrMissing_404() throws Exception {
        when(sessionService.deleteSession(SID))
                .thenThrow(new SessionNotFoundException("会话不存在或已被删除"));

        mockMvc.perform(delete(SESSIONS + "/" + SID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void delete_illegalUuid_400() throws Exception {
        when(sessionService.deleteSession(any()))
                .thenThrow(new InvalidChatRequestException("sessionId 必须为服务端签发的 36 位会话 ID"));

        mockMvc.perform(delete(SESSIONS + "/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---------- PATCH /api/sessions/{id} ----------

    @Test
    void rename_200_returnsSummaryWithoutPreview() throws Exception {
        LocalDateTime updated = LocalDateTime.of(2026, 9, 3, 15, 0, 0);
        // 控制器为薄层、透传原始 title（trim 在 service 层，已在 SessionServiceTest 覆盖）；
        // 切片中 service 被 mock，故按任意 title 入参匹配
        when(sessionService.renameSession(eq(SID), any()))
                .thenReturn(new SessionSummary(SID, "新标题",
                        LocalDateTime.of(2026, 9, 1, 10, 0, 0), updated, null, null));

        String body = mockMvc.perform(patch(SESSIONS + "/" + SID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  新标题  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SID))
                .andExpect(jsonPath("$.title").value("新标题"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // PATCH 响应不含预览字段
        assertThat(body).doesNotContain("\"previewRole\"").doesNotContain("\"previewText\"");
    }

    @Test
    void rename_blankTitle_400() throws Exception {
        when(sessionService.renameSession(any(), any()))
                .thenThrow(new InvalidChatRequestException("title 不能为空"));

        mockMvc.perform(patch(SESSIONS + "/" + SID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void rename_tooLongTitle_400() throws Exception {
        when(sessionService.renameSession(any(), any()))
                .thenThrow(new InvalidChatRequestException("title 长度须为 1–200 字符"));

        mockMvc.perform(patch(SESSIONS + "/" + SID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "字".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void rename_notFound_404() throws Exception {
        when(sessionService.renameSession(eq(SID), any()))
                .thenThrow(new SessionNotFoundException("会话不存在或已被删除"));

        mockMvc.perform(patch(SESSIONS + "/" + SID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    // ---------- 时间字段线格式（R11 钉死） ----------

    @Test
    void localDateTime_serializesToParseableWireFormat() throws Exception {
        when(sessionService.listSessions()).thenReturn(List.of(
                new SessionSummary(SID, "t",
                        LocalDateTime.of(2026, 9, 3, 9, 8, 7),
                        LocalDateTime.of(2026, 9, 3, 14, 30, 15),
                        null, null)));

        String body = mockMvc.perform(get(SESSIONS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 取出 createdAt 值并断言形态（不手拆业务字段，仅钉线格式）
        java.util.regex.Matcher m = Pattern.compile("\"createdAt\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).matches(TS_PATTERN);
    }
}
