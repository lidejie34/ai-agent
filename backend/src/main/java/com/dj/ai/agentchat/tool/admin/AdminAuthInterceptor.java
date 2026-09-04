package com.dj.ai.agentchat.tool.admin;

import com.alibaba.fastjson2.JSON;
import com.dj.ai.agentchat.dto.ApiError;
import com.dj.ai.agentchat.tool.AdminProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管理端鉴权拦截器（插入迭代 G，常驻）：仅拦 {@code /api/admin/**}，
 * 聊天/会话接口路径不匹配、零影响。
 *
 * <p>判定顺序（AC-49/50/51/2）：
 * <ol>
 *   <li>token 未配置（空白）→ 503 {@code ADMIN_NOT_CONFIGURED}（优先级最高）；</li>
 *   <li>工具总开关关闭 → 400 {@code TOOLS_DISABLED}（管理端 web 层常驻，不返回 404）；</li>
 *   <li>缺失/错误 {@code X-Admin-Token} → 401 {@code ADMIN_UNAUTHORIZED}；</li>
 *   <li>全部通过 → 放行。</li>
 * </ol>
 * 错误体与全局异常处理同构（{@code {code,message,timestamp}}，fastjson2 序列化），
 * 不含堆栈/token。
 */
@Slf4j
public class AdminAuthInterceptor implements HandlerInterceptor {

    /** 管理端令牌请求头名。 */
    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AdminProperties adminProperties;
    private final boolean toolsEnabled;

    public AdminAuthInterceptor(AdminProperties adminProperties, boolean toolsEnabled) {
        this.adminProperties = adminProperties;
        this.toolsEnabled = toolsEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String token = adminProperties.getToken();
        if (!StringUtils.hasText(token)) {
            // 未配置令牌：不放行任何管理端请求（本期不记日志，避免时序侧信道外的噪音）
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "ADMIN_NOT_CONFIGURED", "管理端未配置访问令牌");
            return false;
        }
        if (!toolsEnabled) {
            writeError(response, HttpStatus.BAD_REQUEST, "TOOLS_DISABLED", "工具功能未启用");
            return false;
        }
        String presented = request.getHeader(ADMIN_TOKEN_HEADER);
        if (!token.equals(presented)) {
            writeError(response, HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "管理端鉴权失败");
            return false;
        }
        return true;
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        ApiError error = new ApiError(code, message, LocalDateTime.now().withNano(0).format(TS));
        response.getWriter().write(JSON.toJSONString(error));
    }
}
