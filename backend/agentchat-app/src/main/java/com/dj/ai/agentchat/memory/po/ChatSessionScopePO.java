package com.dj.ai.agentchat.memory.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话级范围配置表 {@code chat_session_scope} 实体（迭代12 FR-3）。
 *
 * <p>与 chat_session 1:1；四列 JSON 数组字符串统一三态语义：
 * {@code null}=默认全部；{@code "[]"}=显式全不选；非空=子集。
 * JSON 序列化/反序列化在 service 层（fastjson2），PO 只持字符串原值。
 */
@Data
@TableName("chat_session_scope")
public class ChatSessionScopePO {

    /** 所属会话 UUID（1:1，显式写入主键）。 */
    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    /** 知识库项目过滤 JSON 数组（null=全部）。 */
    private String kbProjects;

    /** 知识库标签过滤 JSON 数组（null=全部）。 */
    private String kbTags;

    /** DB 工具选择 JSON 数组（null=全部）。 */
    private String toolNames;

    /** MCP server 选择 JSON 数组（null=全部 READY）。 */
    private String mcpServers;

    /** 更新时间（DB ON UPDATE CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime updatedAt;
}
