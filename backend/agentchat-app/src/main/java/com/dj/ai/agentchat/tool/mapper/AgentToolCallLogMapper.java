package com.dj.ai.agentchat.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dj.ai.agentchat.tool.po.AgentToolCallLogPO;

/**
 * 工具调用审计表 Mapper（插入迭代 G）。
 *
 * <p>插入用 BaseMapper.insert（call_id 唯一索引兜底幂等，冲突 best-effort 吞掉）；
 * 管理端分页查询用 BaseMapper.selectPage(Page, QueryWrapper)，动态条件
 * （toolName/sessionId/status/时间区间）由 ToolAdminService 按非空拼配。
 * Mapper 扫描随 {@code app.tools.enabled} 条件收口（同 AgentToolMapper）。
 */
public interface AgentToolCallLogMapper extends BaseMapper<AgentToolCallLogPO> {
}
