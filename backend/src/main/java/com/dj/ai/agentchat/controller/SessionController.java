package com.dj.ai.agentchat.controller;

import com.dj.ai.agentchat.dto.session.RenameRequest;
import com.dj.ai.agentchat.dto.session.SessionDeleteResult;
import com.dj.ai.agentchat.dto.session.SessionMessageView;
import com.dj.ai.agentchat.dto.session.SessionSummary;
import com.dj.ai.agentchat.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理接口（迭代4）：薄层，仅协议适配；业务编排/校验/异常归一在 {@link SessionService}。
 *
 * <p>四个端点均常驻组件扫描（无装配缺口）；记忆开关关闭/Manager 缺席时由 service 抛 400，
 * DB 不可达 503、会话不存在 404，统一经 {@code GlobalExceptionHandler} 产出 ApiError。
 * JSON 由 fastjson2 转换器治理（null 字段省略）。
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 会话列表（updated_at 倒序 + 最近一条消息预览；无会话返回空数组）。 */
    @GetMapping
    public List<SessionSummary> listSessions() {
        return sessionService.listSessions();
    }

    /** 某会话全量历史消息（id/时间升序、全文）。 */
    @GetMapping("/{sessionId}/messages")
    public List<SessionMessageView> listMessages(@PathVariable String sessionId) {
        return sessionService.listMessages(sessionId);
    }

    /** 删除会话及其全部消息（同事务级联；重复删除/不存在 404）。 */
    @DeleteMapping("/{sessionId}")
    public SessionDeleteResult deleteSession(@PathVariable String sessionId) {
        return sessionService.deleteSession(sessionId);
    }

    /** 重命名会话（trim 后 1–200 字符；返回更新后会话，不含预览）。 */
    @PatchMapping(value = "/{sessionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionSummary renameSession(@PathVariable String sessionId,
                                        @RequestBody(required = false) RenameRequest request) {
        String title = request == null ? null : request.title();
        return sessionService.renameSession(sessionId, title);
    }
}
