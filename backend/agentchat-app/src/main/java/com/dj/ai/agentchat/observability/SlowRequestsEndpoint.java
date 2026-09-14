package com.dj.ai.agentchat.observability;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 慢请求自定义 actuator 端点（迭代9 FR-4.5）：只读、无参数，
 * {@code GET /actuator/slowrequests} 返回阈值/容量/当前条数/记录清单（新→旧）。
 *
 * <p>序列化走 actuator 自带 Jackson（Map + record 分量，无 fastjson2 依赖）。
 * 条件装配（总开关）+ 暴露由 {@code management.endpoints.web.exposure.include}
 * 白名单控制双保险（R-2）——关闭态端点 bean 不存在，即使白名单误配也无端点。
 * 本地自用不加鉴权（决策 a/c：暴露面默认零、开启即本机 curl）。
 */
@Endpoint(id = "slowrequests")
public class SlowRequestsEndpoint {

    private final SlowRequestTracker tracker;

    public SlowRequestsEndpoint(SlowRequestTracker tracker) {
        this.tracker = tracker;
    }

    @ReadOperation
    public Map<String, Object> slowRequests() {
        List<SlowRequestTracker.SlowRequestEntry> entries = tracker.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("thresholdMs", tracker.thresholdMs());
        body.put("capacity", tracker.capacity());
        body.put("size", entries.size());
        body.put("entries", entries);
        return body;
    }
}
