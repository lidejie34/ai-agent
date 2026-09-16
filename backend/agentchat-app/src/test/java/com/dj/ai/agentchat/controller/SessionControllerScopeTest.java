package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.config.web.FastJsonWebConfig;
import com.dj.ai.agentchat.dto.session.SessionScopeUpdate;
import com.dj.ai.agentchat.dto.session.SessionScopeView;
import com.dj.ai.agentchat.exception.InvalidKbFilterException;
import com.dj.ai.agentchat.exception.SessionNotFoundException;
import com.dj.ai.agentchat.service.SessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话级范围配置端点 Web 切片（迭代12 FR-3）：{@link SessionService} 被 mock，
 * 只验证协议适配——GET/PUT 200 形态、fastjson2 null 省略、404/400 错误体。
 */
@WebMvcTest(SessionController.class)
@Import(FastJsonWebConfig.class)
class SessionControllerScopeTest {

    private static final String SID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SCOPE = "/api/sessions/" + SID + "/scope";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionService sessionService;

    @Test
    void getScope_200_shapeWithNullsOmitted() throws Exception {
        when(sessionService.getScope(SID)).thenReturn(new SessionScopeView(
                List.of("订单域"), null, List.of("analyze_log"), List.of("easy-mysql")));

        mockMvc.perform(get(SCOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbProjects[0]").value("订单域"))
                .andExpect(jsonPath("$.toolNames[0]").value("analyze_log"))
                .andExpect(jsonPath("$.mcpServers[0]").value("easy-mysql"))
                // fastjson2 默认省略 null 键（三态 null 不下发）
                .andExpect(jsonPath("$.kbTags").doesNotExist());
    }

    @Test
    void getScope_sessionMissing_404() throws Exception {
        when(sessionService.getScope(SID))
                .thenThrow(new SessionNotFoundException("会话不存在或已被删除"));

        mockMvc.perform(get(SCOPE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void putScope_200_echoesNormalizedView() throws Exception {
        when(sessionService.updateScope(eq(SID), any())).thenReturn(new SessionScopeView(
                List.of("订单域"), null, List.of("analyze_log"), null));

        mockMvc.perform(put(SCOPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kbProjects\":[\"订单域\"],\"toolNames\":[\"analyze_log\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbProjects[0]").value("订单域"))
                .andExpect(jsonPath("$.toolNames[0]").value("analyze_log"));

        ArgumentCaptor<SessionScopeUpdate> cap = ArgumentCaptor.forClass(SessionScopeUpdate.class);
        verify(sessionService).updateScope(eq(SID), cap.capture());
        assertThat(cap.getValue().kbProjects()).containsExactly("订单域");
        assertThat(cap.getValue().toolNames()).containsExactly("analyze_log");
        // 未携带字段反序列化为 null（三态保持）
        assertThat(cap.getValue().kbTags()).isNull();
        assertThat(cap.getValue().mcpServers()).isNull();
    }

    @Test
    void putScope_emptyJson_allFieldsNull() throws Exception {
        when(sessionService.updateScope(eq(SID), any())).thenReturn(SessionScopeView.ALL_DEFAULT);

        mockMvc.perform(put(SCOPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SessionScopeUpdate> cap = ArgumentCaptor.forClass(SessionScopeUpdate.class);
        verify(sessionService).updateScope(eq(SID), cap.capture());
        assertThat(cap.getValue().kbProjects()).isNull();
        assertThat(cap.getValue().kbTags()).isNull();
        assertThat(cap.getValue().toolNames()).isNull();
        assertThat(cap.getValue().mcpServers()).isNull();
    }

    @Test
    void putScope_invalidKbFilter_400() throws Exception {
        when(sessionService.updateScope(eq(SID), any()))
                .thenThrow(new InvalidKbFilterException("知识库过滤参数非法：项目名含非法字符"));

        mockMvc.perform(put(SCOPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kbProjects\":[\"含,逗号\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_INVALID_FILTER"));
    }
}
