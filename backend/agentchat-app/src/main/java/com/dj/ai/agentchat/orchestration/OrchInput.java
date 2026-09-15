package com.dj.ai.agentchat.orchestration;

import com.dj.ai.agentchat.dto.KbFilter;
import com.dj.ai.agentchat.tool.support.ToolMount;
import org.springframework.ai.chat.messages.Message;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * ChatService → {@link OrchestrationService} 的每轮编排输入（迭代5，T4）。
 *
 * @param runId          跑次 ID（= 共享 mount 的 requestId；无 mount 时 UUID）
 * @param userText       用户本轮原始消息
 * @param history        ChatService 已加载的主会话历史（编排层不接触 ConversationStore，AC-27）
 * @param sessionId      主会话 ID（无状态=null），仅用于审计
 * @param toolMount      整轮共享工具挂载（null=无工具；Executor 唯一使用方）
 * @param totalBudget    总墙钟预算（SSE 110s / 同步 55s，短于容器超时）
 * @param streaming      true=SSE（发帧）；false=同步（bridge noop）
 * @param kbFilter       知识库检索维度过滤（迭代10，可空）：经 OrchestrationService
 *                       透传 ExecutorClient 注入 RagAdvisor param；null=全库检索
 * @param degradeStream  路由失败降级：迭代4 普通流式（Flux.defer + 首片段重试，不含落库尾管）
 * @param degradeCall    路由失败降级：迭代4 普通同步调用（返回回复纯文本）
 */
public record OrchInput(String runId,
                        String userText,
                        List<Message> history,
                        String sessionId,
                        ToolMount toolMount,
                        Duration totalBudget,
                        boolean streaming,
                        @Nullable KbFilter kbFilter,
                        Supplier<Flux<String>> degradeStream,
                        Supplier<String> degradeCall) {

    /**
     * 迭代9 前兼容构造：无知识库过滤（null=全库检索），既有构造点零改动。
     */
    public OrchInput(String runId, String userText, List<Message> history, String sessionId,
                     ToolMount toolMount, Duration totalBudget, boolean streaming,
                     Supplier<Flux<String>> degradeStream, Supplier<String> degradeCall) {
        this(runId, userText, history, sessionId, toolMount, totalBudget, streaming,
                null, degradeStream, degradeCall);
    }
}
