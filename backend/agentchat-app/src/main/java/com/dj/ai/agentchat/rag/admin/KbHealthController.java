package com.dj.ai.agentchat.rag.admin;

import com.dj.ai.agentchat.rag.admin.dto.KbHealthView;
import com.dj.ai.agentchat.rag.admin.service.KbHealthService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库健康检查端点（迭代6 #87）：{@code GET /api/admin/kb/health}。
 *
 * <p>常驻装配（同 {@link KbAdminController}）：{@code app.rag.enabled=false} 时请求被
 * {@code AdminAuthInterceptor} 路径闸门挡为 503 {@code KB_DISABLED}；service bean 缺席时
 * 防御性返回 {@link KbHealthView#disabled()}（enabled=false），不抛异常。
 * 探测仅在人工点「刷新」时触发，不做轮询，不挂任何启动/定时链路。
 */
@RestController
@RequestMapping("/api/admin/kb")
public class KbHealthController {

    private final ObjectProvider<KbHealthService> healthServiceProvider;

    public KbHealthController(ObjectProvider<KbHealthService> healthServiceProvider) {
        this.healthServiceProvider = healthServiceProvider;
    }

    @GetMapping("/health")
    public KbHealthView health() {
        KbHealthService service = healthServiceProvider.getIfAvailable();
        return service == null ? KbHealthView.disabled() : service.health();
    }
}
