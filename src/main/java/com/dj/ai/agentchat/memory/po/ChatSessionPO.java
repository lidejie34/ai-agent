package com.dj.ai.agentchat.memory.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表 {@code chat_session} 实体（迭代3 FR-15）。
 *
 * <p>session_id 为服务端生成的 36 位 UUID，{@link IdType#INPUT} 显式写入；
 * title 本期恒为 {@code null}（列预留）；created_at/updated_at 由 DDL 默认值与
 * ON UPDATE 维护，插入时留 null（MP NOT_NULL 策略不进 INSERT 列）。
 */
@Data
@TableName("chat_session")
public class ChatSessionPO {

    /** 会话 UUID（服务端生成，显式写入主键）。 */
    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    /** 会话标题（本期不写入，恒 null）。 */
    private String title;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime createdAt;

    /** 更新时间（DB ON UPDATE CURRENT_TIMESTAMP 维护）。 */
    private LocalDateTime updatedAt;
}
