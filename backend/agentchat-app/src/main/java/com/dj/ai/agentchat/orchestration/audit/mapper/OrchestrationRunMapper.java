package com.dj.ai.agentchat.orchestration.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dj.ai.agentchat.orchestration.audit.po.OrchestrationRunPO;

/**
 * 编排跑次审计表 Mapper（迭代5，T1）。
 *
 * <p>仅 BaseMapper.insert（best-effort 审计写入）；本期不提供查询接口/管理页面（AC-60.5）。
 * Mapper 扫描随 {@code app.sdd.enabled} 条件收口（接口不标 {@code @Mapper}，
 * MyBatis 自动扫描不兜底，AC-1）。
 */
public interface OrchestrationRunMapper extends BaseMapper<OrchestrationRunPO> {
}
