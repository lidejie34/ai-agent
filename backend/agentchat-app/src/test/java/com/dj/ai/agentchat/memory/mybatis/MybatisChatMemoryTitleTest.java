package com.dj.ai.agentchat.memory.mybatis;

import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.po.ChatMessagePO;
import com.dj.ai.agentchat.util.TextTitleUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2：{@link MybatisChatMemory#add} 首轮标题 best-effort 写入（FR-4）。
 * 标题取批次中第一条 USER 消息、按 {@link TextTitleUtils} 截断；写标题失败仅 log.error
 * 吞掉，不影响消息 insert（事务代理见不到异常，FR-4.4）。幂等「二轮不覆盖」由 SQL
 * {@code AND title IS NULL} 保证（见 MybatisSessionManagerTest 的注解断言）。
 */
class MybatisChatMemoryTitleTest {

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

    @Test
    void add_firstTurn_writesTruncatedTitleFromFirstUserMessage() {
        String longUserText = "  这是一段非常非常非常非常非常非常长的首轮用户消息需要被截断 ";
        List<Message> messages = List.of(
                new UserMessage(longUserText),
                new AssistantMessage("好的"));

        memory.add(SID, messages);

        String expectedTitle = TextTitleUtils.buildTitle(longUserText);
        verify(chatSessionMapper).updateTitleIfAbsent(SID, expectedTitle);
        assertThat(expectedTitle).endsWith("…");
    }

    @Test
    void add_seedBatch_usesFirstUserMessageInBatch() {
        // seed(历史 user/assistant) + 本轮 user/assistant：标题取批次首条 USER（seed 首问）
        String seedFirstUser = "我喜欢😀我喜欢😀我喜欢😀我喜欢😀我喜欢😀超长部分";
        List<Message> messages = List.of(
                new UserMessage(seedFirstUser),
                new AssistantMessage("好的"),
                new UserMessage("第二个问题"),
                new AssistantMessage("回答"));

        memory.add(SID, messages);

        ArgumentCaptor<String> titleCap = ArgumentCaptor.forClass(String.class);
        verify(chatSessionMapper).updateTitleIfAbsent(eq(SID), titleCap.capture());
        assertThat(titleCap.getValue()).isEqualTo(TextTitleUtils.buildTitle(seedFirstUser));
        // 消息仍逐条落库
        verify(chatMessageMapper, times(4)).insert(any(ChatMessagePO.class));
    }

    @Test
    void add_batchWithoutUserMessage_doesNotWriteTitle() {
        memory.add(SID, List.of(new AssistantMessage("仅助手消息")));

        verify(chatSessionMapper, never()).updateTitleIfAbsent(any(), any());
    }

    @Test
    void add_titleMapperThrows_addDoesNotThrowAndMessagesStillInserted() {
        when(chatSessionMapper.updateTitleIfAbsent(any(), any()))
                .thenThrow(new RuntimeException("simulated-db-title-failure"));

        assertThatCode(() -> memory.add(SID, List.of(
                new UserMessage("你好"),
                new AssistantMessage("你好呀"))))
                .doesNotThrowAnyException();

        // 消息 insert 照常（降级：标题失败不拖累主流程）
        verify(chatMessageMapper, times(2)).insert(any(ChatMessagePO.class));
    }

    @Test
    void add_stillAnnotatedTransactional() throws NoSuchMethodException {
        Method method = MybatisChatMemory.class.getMethod("add", String.class, List.class);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }
}
