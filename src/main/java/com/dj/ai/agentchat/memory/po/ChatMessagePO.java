package com.dj.ai.agentchat.memory.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息表 {@code chat_message} 实体（迭代3 FR-13/FR-15）。
 *
 * <p>id 为数据库自增主键（{@link IdType#AUTO}）；role 仅 {@code user}/{@code assistant}
 * （system 本期不入库，TOOL 类型防御性拒绝）；content 为 MEDIUMTEXT 全文不截断；
 * created_at 由 DDL 默认值维护，插入时留 null。
 */
@Data
@TableName("chat_message")
public class ChatMessagePO {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属会话 UUID。 */
    private String sessionId;

    /** 角色：user / assistant。 */
    private String role;

    /** 消息全文（MEDIUMTEXT，不截断）。 */
    private String content;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime createdAt;
}
