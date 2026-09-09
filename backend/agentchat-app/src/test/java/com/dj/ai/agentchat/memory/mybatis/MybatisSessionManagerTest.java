package com.dj.ai.agentchat.memory.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.memory.po.ChatSessionPO;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2：{@link MybatisSessionManager} 单测——离线 mock mapper/SchemaInitializer，
 * 验证委托、排序参数透传、级联删除顺序、重命名局部更新；真实 MySQL 语义留 T5 冒烟。
 */
class MybatisSessionManagerTest {

    private static final String SID = "123e4567-e89b-12d3-a456-426614174000";

    private ChatSessionMapper chatSessionMapper;
    private ChatMessageMapper chatMessageMapper;
    private ChatMemorySchemaInitializer schemaInitializer;
    private MybatisSessionManager manager;

    @BeforeEach
    void setUp() {
        chatSessionMapper = mock(ChatSessionMapper.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        schemaInitializer = mock(ChatMemorySchemaInitializer.class);
        manager = new MybatisSessionManager(chatSessionMapper, chatMessageMapper, schemaInitializer);
    }

    // ---------- listSessions：updated_at 倒序委托 ----------

    @Test
    void listSessions_delegatesWithOrderByUpdatedAtDesc() {
        ChatSessionPO p1 = sessionPo(SID, "标题一");
        ChatSessionPO p2 = sessionPo("223e4567-e89b-12d3-a456-426614174001", null);
        when(chatSessionMapper.selectList(any())).thenReturn(List.of(p1, p2));

        List<ChatSessionPO> result = manager.listSessions();

        assertThat(result).containsExactly(p1, p2);
        ArgumentCaptor<QueryWrapper<ChatSessionPO>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(chatSessionMapper).selectList(cap.capture());
        String segment = cap.getValue().getSqlSegment().toUpperCase();
        assertThat(segment).contains("ORDER BY").contains("UPDATED_AT DESC");
        verify(schemaInitializer).ensureSchema();
    }

    // ---------- latestMessages：批量 IN 透传，空集合不发查询（避免 IN () ） ----------

    @Test
    void latestMessages_delegatesBatchToMapper() {
        ChatMessagePO m = messagePo(SID, "assistant", "预览");
        when(chatMessageMapper.selectLatestPerSession(any())).thenReturn(List.of(m));

        List<ChatMessagePO> result = manager.latestMessages(List.of(SID, "other"));

        assertThat(result).containsExactly(m);
        ArgumentCaptor<Collection<String>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(chatMessageMapper).selectLatestPerSession(cap.capture());
        assertThat(cap.getValue()).containsExactlyInAnyOrder(SID, "other");
    }

    @Test
    void latestMessages_emptyCollection_skipsQuery() {
        assertThat(manager.latestMessages(List.of())).isEmpty();
        verify(chatMessageMapper, never()).selectLatestPerSession(any());
    }

    // ---------- listMessagesAscending：全量升序 ----------

    @Test
    void listMessagesAscending_delegatesToSelectAllAscending() {
        when(chatMessageMapper.selectAllAscending(SID))
                .thenReturn(List.of(messagePo(SID, "user", "一"), messagePo(SID, "assistant", "二")));

        List<ChatMessagePO> result = manager.listMessagesAscending(SID);

        assertThat(result).hasSize(2);
        verify(chatMessageMapper).selectAllAscending(SID);
        verify(schemaInitializer).ensureSchema();
    }

    // ---------- findSession ----------

    @Test
    void findSession_delegatesToSelectById() {
        ChatSessionPO po = sessionPo(SID, "标题");
        when(chatSessionMapper.selectById(SID)).thenReturn(po);

        assertThat(manager.findSession(SID)).isSameAs(po);
        verify(chatSessionMapper).selectById(SID);
        verify(schemaInitializer).ensureSchema();
    }

    // ---------- deleteCascade：同事务先删消息后删会话 ----------

    @Test
    void deleteCascade_deletesMessagesThenSession_inOrder() {
        manager.deleteCascade(SID);

        InOrder inOrder = inOrder(chatMessageMapper, chatSessionMapper);
        inOrder.verify(chatMessageMapper).deleteBySessionId(SID);
        inOrder.verify(chatSessionMapper).deleteById(SID);
        verify(schemaInitializer).ensureSchema();
    }

    @Test
    void deleteCascade_isTransactional() throws NoSuchMethodException {
        Method method = MybatisSessionManager.class.getMethod("deleteCascade", String.class);
        assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .isNotNull();
    }

    // ---------- rename：局部更新 title 并回读 ----------

    @Test
    void rename_updatesTitleOnly_andReReads() {
        ChatSessionPO refreshed = sessionPo(SID, "新标题");
        when(chatSessionMapper.selectById(SID)).thenReturn(refreshed);

        ChatSessionPO result = manager.rename(SID, "新标题");

        ArgumentCaptor<ChatSessionPO> cap = ArgumentCaptor.forClass(ChatSessionPO.class);
        verify(chatSessionMapper).updateById(cap.capture());
        assertThat(cap.getValue().getSessionId()).isEqualTo(SID);
        assertThat(cap.getValue().getTitle()).isEqualTo("新标题");
        // createdAt/updatedAt 不进 SET（updated_at 由 MySQL ON UPDATE 维护）
        assertThat(cap.getValue().getCreatedAt()).isNull();
        assertThat(cap.getValue().getUpdatedAt()).isNull();
        verify(chatSessionMapper).selectById(SID);
        assertThat(result).isSameAs(refreshed);
        verify(schemaInitializer).ensureSchema();
    }

    // ---------- Mapper 注解 SQL 静态断言 ----------

    @Test
    void messageMapper_selectAllAscending_isFullOrderedAsc() throws Exception {
        Method method = ChatMessageMapper.class.getMethod("selectAllAscending", String.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();
        assertThat(sql).contains("ORDER BY ID ASC");
        assertThat(sql).doesNotContain("LIMIT");
    }

    @Test
    void messageMapper_selectLatestPerSession_usesJoinMaxGroupBy() throws Exception {
        Method method = ChatMessageMapper.class.getMethod("selectLatestPerSession", Collection.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();
        assertThat(sql).contains("MAX(ID)").contains("INNER JOIN").contains("GROUP BY SESSION_ID");
        assertThat(sql).contains("<FOREACH").contains("SESSION_ID IN");
    }

    @Test
    void sessionMapper_updateTitleIfAbsent_guardedByTitleNull() throws Exception {
        Method method = ChatSessionMapper.class.getMethod("updateTitleIfAbsent", String.class, String.class);
        Update update = method.getAnnotation(Update.class);
        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ").toUpperCase();
        assertThat(sql).contains("UPDATE CHAT_SESSION SET TITLE");
        assertThat(sql).contains("WHERE SESSION_ID = #{SESSIONID}").contains("TITLE IS NULL");
    }

    private static ChatSessionPO sessionPo(String sid, String title) {
        ChatSessionPO po = new ChatSessionPO();
        po.setSessionId(sid);
        po.setTitle(title);
        return po;
    }

    private static ChatMessagePO messagePo(String sid, String role, String content) {
        ChatMessagePO po = new ChatMessagePO();
        po.setSessionId(sid);
        po.setRole(role);
        po.setContent(content);
        return po;
    }
}
