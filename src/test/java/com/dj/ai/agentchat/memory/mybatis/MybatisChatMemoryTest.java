package com.dj.ai.agentchat.memory.mybatis;

import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3：{@link MybatisChatMemory} 单测——离线 mock 两个 BaseMapper 与 SchemaInitializer
 * （不引 @MybatisPlusTest/H2/Testcontainers）；真实 MySQL SQL 语义留 T7 冒烟（AC-19/20）。
 */
class MybatisChatMemoryTest {

    private static final String SID = "123e4567-e89b-12d3-a456-426614174000";

    private ChatSessionMapper chatSessionMapper;
    private ChatMessageMapper chatMessageMapper;
    private ChatMemorySchemaInitializer schemaInitializer;
    private MybatisChatMemory memory;

    @BeforeEach
    void setUp() {
        chatSessionMapper = mock(ChatSessionMapper.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        schemaInitializer = mock(ChatMemorySchemaInitializer.class);
        memory = new MybatisChatMemory(chatSessionMapper, chatMessageMapper, schemaInitializer);
    }

    // ---------- add：成对落库、PO 字段/顺序 ----------

    @Test
    void add_persistsEachMessageAsPo_withCorrectSessionRoleContentOrder() {
        List<Message> messages = List.of(
                new UserMessage("你好"),
                new AssistantMessage("你好，有什么可以帮你？"));

        memory.add(SID, messages);

        ArgumentCaptor<ChatMessagePO> captor = ArgumentCaptor.forClass(ChatMessagePO.class);
        verify(chatMessageMapper, times(2)).insert(captor.capture());
        List<ChatMessagePO> pos = captor.getAllValues();

        assertThat(pos.get(0).getSessionId()).isEqualTo(SID);
        assertThat(pos.get(0).getRole()).isEqualTo("user");
        assertThat(pos.get(0).getContent()).isEqualTo("你好");
        assertThat(pos.get(1).getRole()).isEqualTo("assistant");
        assertThat(pos.get(1).getContent()).isEqualTo("你好，有什么可以帮你？");
        // id/createdAt 留空：走 DB 自增/默认值（MP NOT_NULL 策略不进 INSERT 列）
        assertThat(pos.get(0).getId()).isNull();
        assertThat(pos.get(0).getCreatedAt()).isNull();
        verify(schemaInitializer).ensureSchema();
    }

    @Test
    void add_seedPlusUserAssistant_fourRowsInOrder() {
        // seed-once 场景：seed(user,assistant) + 本轮 user + assistant 共 4 条同批写入
        List<Message> messages = List.of(
                new UserMessage("我叫小明"),
                new AssistantMessage("你好小明"),
                new UserMessage("我叫什么？"),
                new AssistantMessage("你叫小明"));

        memory.add(SID, messages);

        ArgumentCaptor<ChatMessagePO> captor = ArgumentCaptor.forClass(ChatMessagePO.class);
        verify(chatMessageMapper, times(4)).insert(captor.capture());
        List<ChatMessagePO> pos = captor.getAllValues();
        assertThat(pos).extracting(ChatMessagePO::getRole)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(pos).allSatisfy(po -> assertThat(po.getSessionId()).isEqualTo(SID));
        assertThat(pos.get(3).getContent()).isEqualTo("你叫小明");
    }

    @Test
    void add_systemMessage_mapsToSystemRole_toolRejected() {
        memory.add(SID, List.of(new SystemMessage("你是助手"), new UserMessage("hi")));

        ArgumentCaptor<ChatMessagePO> captor = ArgumentCaptor.forClass(ChatMessagePO.class);
        verify(chatMessageMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getRole()).isEqualTo("system");

        // TOOL 等非 user/assistant/system 角色防御性拒绝
        Message toolMessage = mock(Message.class);
        when(toolMessage.getMessageType()).thenReturn(MessageType.TOOL);
        when(toolMessage.getText()).thenReturn("tool-result");
        assertThatThrownBy(() -> memory.add(SID, List.of(toolMessage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    // ---------- get：最近 N 条正序还原 ----------

    @Test
    void get_restoresMessagesInChronologicalOrder_withLastNParam() {
        when(chatMessageMapper.selectRecent(eq(SID), eq(20))).thenReturn(List.of(
                po("user", "历史问题"),
                po("assistant", "历史回答"),
                po("user", "本轮问题")));

        List<Message> result = memory.get(SID, 20);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(result.get(0).getText()).isEqualTo("历史问题");
        assertThat(result.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(result.get(1).getText()).isEqualTo("历史回答");
        assertThat(result.get(2).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(result.get(2).getText()).isEqualTo("本轮问题");
        verify(chatMessageMapper).selectRecent(SID, 20);
        verify(schemaInitializer).ensureSchema();
    }

    // ---------- createSessionIfAbsent / clear ----------

    @Test
    void createSessionIfAbsent_delegatesToInsertIgnore() {
        memory.createSessionIfAbsent(SID);

        verify(chatSessionMapper).insertIgnoreSession(SID);
        verify(schemaInitializer).ensureSchema();
    }

    @Test
    void clear_deletesBySessionId() {
        memory.clear(SID);

        verify(chatMessageMapper).deleteBySessionId(SID);
        verify(schemaInitializer).ensureSchema();
    }

    // ---------- Mapper 注解 SQL 静态断言（实证 4 的 @Insert/@Select 手法固化） ----------

    @Test
    void sessionMapper_insertIgnore_isIdempotentSql() throws Exception {
        Method method = ChatSessionMapper.class.getMethod("insertIgnoreSession", String.class);
        Insert insert = method.getAnnotation(Insert.class);
        assertThat(insert).isNotNull();
        String sql = String.join(" ", insert.value());
        assertThat(sql).contains("INSERT IGNORE INTO chat_session");
        assertThat(sql).contains("#{sessionId}");
    }

    @Test
    void messageMapper_selectRecent_innerDescLimitOuterAsc() throws Exception {
        Method method = ChatMessageMapper.class
                .getMethod("selectRecent", String.class, int.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");
        assertThat(sql).contains("ORDER BY id DESC LIMIT #{n}");
        assertThat(sql).contains("ORDER BY t.id ASC");
        assertThat(sql).contains("session_id = #{sessionId}");
    }

    private static ChatMessagePO po(String role, String content) {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(SID);
        po.setRole(role);
        po.setContent(content);
        return po;
    }
}
