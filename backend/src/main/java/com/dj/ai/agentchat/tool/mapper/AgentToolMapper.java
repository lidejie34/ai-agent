package com.dj.ai.agentchat.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dj.ai.agentchat.tool.po.AgentToolPO;

/**
 * 工具注册表 Mapper（插入迭代 G）。
 *
 * <p>不标 {@code @Mapper}：扫描统一由 {@code ToolRuntimeConfig} 上的
 * {@code @MapperScan} 随 {@code app.tools.enabled} 条件收口（enabled=false 时
 * Mapper bean 不装配，范式同 ChatSessionMapper）。装载/重名检查/CRUD 均用
 * BaseMapper + QueryWrapper（无 XML、无自定义注解 SQL）。
 */
public interface AgentToolMapper extends BaseMapper<AgentToolPO> {
}
