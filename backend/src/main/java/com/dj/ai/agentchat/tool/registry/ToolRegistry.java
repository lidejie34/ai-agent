package com.dj.ai.agentchat.tool.registry;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dj.ai.agentchat.exception.ToolsUnavailableException;
import com.dj.ai.agentchat.tool.callback.SkippableToolException;
import com.dj.ai.agentchat.tool.callback.ToolCallbackFactory;
import com.dj.ai.agentchat.tool.mapper.AgentToolMapper;
import com.dj.ai.agentchat.tool.po.AgentToolPO;
import com.dj.ai.agentchat.tool.schema.ToolSchemaInitializer;
import com.dj.ai.agentchat.tool.schema.ToolSeeder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册中心（插入迭代 G）：DB 装载 → ToolCallback → volatile 内存快照。
 *
 * <p>对话路径 {@link #toolCallbacks()}：快照已装载则零 DB 直接返回（AC-12）；
 * 冷启动/上次失败时尝试装载——DB 故障降级为空集 + WARN「工具装载失败」（工具是增强
 * 能力，对话不 5xx），且<b>不缓存失败</b>，下次请求重试、DB 恢复自愈（AC-13）。
 * 坏行（未知类型/非法 config/bean 或脚本缺失）跳过 + WARN，不影响其余行（AC-15/16）。
 *
 * <p>管理端写操作后 {@link #refresh()} 强制重载；失败抛 {@link ToolsUnavailableException}
 * （503，数据已落库，提示重试）。
 */
@Slf4j
public class ToolRegistry {

    private final AgentToolMapper toolMapper;
    private final ToolCallbackFactory callbackFactory;
    private final ToolSchemaInitializer schemaInitializer;
    private final ToolSeeder toolSeeder;

    private volatile ToolLoadSnapshot snapshot = ToolLoadSnapshot.empty();

    public ToolRegistry(AgentToolMapper toolMapper,
                        ToolCallbackFactory callbackFactory,
                        ToolSchemaInitializer schemaInitializer,
                        ToolSeeder toolSeeder) {
        this.toolMapper = toolMapper;
        this.callbackFactory = callbackFactory;
        this.schemaInitializer = schemaInitializer;
        this.toolSeeder = toolSeeder;
    }

    /**
     * 对话路径：返回当前应挂载的回调集（永不为 null；故障/空表语义为空列表）。
     */
    public List<ToolCallback> toolCallbacks() {
        ToolLoadSnapshot current = snapshot;
        if (current.loaded()) {
            return current.callbacks();
        }
        return reload(true).callbacks();
    }

    /**
     * 管理端写操作后强制刷新；DB 失败抛 {@link ToolsUnavailableException}（503）。
     */
    public synchronized void refresh() {
        ToolLoadSnapshot reloaded = reload(false);
        if (!reloaded.ok()) {
            throw new ToolsUnavailableException("工具服务暂不可用（数据库访问失败）");
        }
        log.info("工具缓存刷新: 启用工具数={}", reloaded.callbacks().size());
    }

    private ToolLoadSnapshot reload(boolean degradeOnError) {
        try {
            // 懒建表 + 懒种子（启动 Runner 失败时，首次工具路径自愈补齐）
            schemaInitializer.ensureSchema();
            toolSeeder.seedIfAbsent();
            List<AgentToolPO> rows = toolMapper.selectList(
                    new QueryWrapper<AgentToolPO>().eq("enabled", 1).orderByAsc("id"));
            List<ToolCallback> callbacks = new ArrayList<>();
            for (AgentToolPO row : rows) {
                try {
                    callbacks.add(callbackFactory.build(row));
                } catch (SkippableToolException e) {
                    // 坏行跳过：单行配置问题不影响其余工具（AC-15/16）
                    log.warn("工具装载跳过: toolName={}, 原因={}", row.getToolName(), e.getMessage());
                }
            }
            ToolLoadSnapshot reloaded = ToolLoadSnapshot.loaded(callbacks);
            this.snapshot = reloaded;
            log.info("工具装载完成: 启用工具数={}", callbacks.size());
            return reloaded;
        } catch (DataAccessException e) {
            // 对话路径降级空集（WARN 关键字 AC-13）；失败快照不缓存 → 下次请求重试自愈
            log.warn("工具装载失败，本次对话降级为无工具: {}", e.getMessage());
            if (!degradeOnError) {
                throw new ToolsUnavailableException("工具服务暂不可用", e);
            }
            return ToolLoadSnapshot.failed();
        }
    }
}
