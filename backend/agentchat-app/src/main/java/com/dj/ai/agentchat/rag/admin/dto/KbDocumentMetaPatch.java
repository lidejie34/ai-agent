package com.dj.ai.agentchat.rag.admin.dto;

import java.util.List;

/**
 * 文档维度元数据 PATCH 请求体（迭代10）：<b>全量替换语义</b>——两字段必含，
 * {@code project}=null 清除归属、{@code tags}=空数组清空标签；无 absent/null 二义性。
 * 文件内容/文件名不可经此接口修改。
 */
public record KbDocumentMetaPatch(String project, List<String> tags) {
}
