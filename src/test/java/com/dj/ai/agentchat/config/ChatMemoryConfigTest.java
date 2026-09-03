package com.dj.ai.agentchat.config;

import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import com.dj.ai.agentchat.dto.ChatRequest;
import com.dj.ai.agentchat.dto.ChatResponse;
import com.dj.ai.agentchat.exception.InvalidChatRequestException;
import com.dj.ai.agentchat.memory.ChatMemoryProperties;
import com.dj.ai.agentchat.memory.ConversationStore;
import com.dj.ai.agentchat.memory.mapper.ChatMessageMapper;
import com.dj.ai.agentchat.memory.mapper.ChatSessionMapper;
import com.dj.ai.agentchat.memory.mybatis.ChatMemorySchemaInitializer;
import com.dj.ai.agentchat.memory.mybatis.ChatMemorySchemaStartupRunner;
import com.dj.ai.agentchat.memory.mybatis.MybatisChatMemory;
import com.dj.ai.agentchat.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T6：记忆层开关与上下文装配（FR-6 / AC-12/13/14）。
 *
 * <p>两个切片：
 * <ol>
 *   <li>{@link ChatMemoryConfigTest}：默认（enabled=true，DB 不可达）上下文刷新成功，
 *       Store/Initializer/Runner/Mapper/Properties 全部装配，fastjson2 转换器居首；
 *       mock ChatModel 后无状态同步请求在「DB 不可达」下依然成功且 sessionId=null
 *            ——任何记忆 bean 触碰都会尝试建表/连库并抛 503，成功即证明无状态路径零 DB 交互。</li>
 *   <li>{@link ChatMemoryDisabledContextTest}：enabled=false 时记忆全家桶 bean 全部缺席，
 *       ChatService 仍装配；会话路径（新建/续接）同步段即 400（记忆未启用），不触网。</li>
 * </ol>
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=ark-context-test-key")
class ChatMemoryConfigTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ChatService chatService;

    /** 替换方舟模型 bean：上下文测试不发起任何真实 HTTP（离线）。 */
    @MockitoBean
    private ChatModel chatModel;

    @Test
    void memoryBeans_arePresent_byDefault() {
        assertThat(context.getBean(ConversationStore.class)).isInstanceOf(MybatisChatMemory.class);
        assertThat(context.getBeansOfType(ChatMemorySchemaInitializer.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ChatMemorySchemaStartupRunner.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ChatSessionMapper.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ChatMessageMapper.class)).isNotEmpty();
    }

    @Test
    void memoryProperties_bindDefaults() {
        ChatMemoryProperties props = context.getBean(ChatMemoryProperties.class);
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxHistory()).isEqualTo(20);
        assertThat(props.isInitOnStartup()).isTrue();
    }

    @Test
    void fastJsonHttpMessageConverter_precedesJacksonInMvcConverterChain() {
        // WebMvcConfigurer.extendMessageConverters 作用于 MVC 处理链（@ResponseBody 序列化
        // 实际使用的链），而非 Boot 的 HttpMessageConverters bean。
        // 全量上下文中 spring-data-web 的 ProjectingJackson2HttpMessageConverter 可能经其
        // 自身 WebMvcConfigurer 插到更前（它 canWrite 仅对投影代理生效，普通 DTO 不消费）；
        // 关键保证是 fastjson2 排在「通用 Jackson」之前 —— 普通对象 application/json
        // 序列化按链序选第一个 canWrite 的转换器，fastjson 胜出即线网格式由其治理
        // （字段名/空值省略；切片测试 FastJsonWebConfigTest 已端到端实证）。
        RequestMappingHandlerAdapter adapter = context.getBean(RequestMappingHandlerAdapter.class);
        List<org.springframework.http.converter.HttpMessageConverter<?>> converters =
                adapter.getMessageConverters();

        int fastJsonIndex = -1;
        int jacksonIndex = -1;
        for (int i = 0; i < converters.size(); i++) {
            org.springframework.http.converter.HttpMessageConverter<?> c = converters.get(i);
            if (c instanceof FastJsonHttpMessageConverter && fastJsonIndex < 0) {
                fastJsonIndex = i;
            }
            // 精确匹配通用 Jackson（排除 spring-data 的 ProjectingJackson2HttpMessageConverter）
            if (c.getClass() == org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class
                    && jacksonIndex < 0) {
                jacksonIndex = i;
            }
        }
        assertThat(fastJsonIndex).as("fastjson2 转换器必须在 MVC 链中").isGreaterThanOrEqualTo(0);
        assertThat(jacksonIndex).as("通用 Jackson 转换器应在链中").isGreaterThanOrEqualTo(0);
        assertThat(fastJsonIndex).as("fastjson2 必须排在通用 Jackson 之前")
                .isLessThan(jacksonIndex);
    }

    @Test
    void statelessSync_succeedsWithDatabaseDown_andSessionIdNull() {
        // DB（127.0.0.1:13306）测试环境不可达：无状态路径若触碰任何记忆 bean，
        // ensureSchema 会连库失败并抛 MemoryUnavailableException(503)；成功返回即零 DB 交互证据。
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("无状态回答")))));

        ChatResponse response = chatService.chat(new ChatRequest("你好", null, null));

        assertThat(response.reply()).isEqualTo("无状态回答");
        assertThat(response.sessionId()).isNull();
    }
}

/**
 * T6：app.chat.memory.enabled=false —— 记忆全家桶不装配，应用上下文照常刷新。
 */
@SpringBootTest(properties = {
        "app.chat.memory.enabled=false",
        "spring.ai.openai.api-key=ark-context-test-key"
})
class ChatMemoryDisabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ChatService chatService;

    @Test
    void memoryBeans_areAbsent_whenDisabled() {
        assertThat(context.getBeansOfType(ConversationStore.class)).isEmpty();
        assertThat(context.getBeansOfType(MybatisChatMemory.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMemorySchemaInitializer.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMemorySchemaStartupRunner.class)).isEmpty();
        // @MapperScan 随 ChatMemoryConfig 一同不生效：Mapper 代理 bean 也不存在
        assertThat(context.getBeansOfType(ChatSessionMapper.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatMessageMapper.class)).isEmpty();
    }

    @Test
    void applicationStillBoots_dataSourcePresent_chatServiceWired() {
        assertThat(context.getBean(DataSource.class)).isNotNull();
        assertThat(context.getBean(ChatService.class)).isSameAs(chatService);
    }

    @Test
    void newSessionPath_rejectedWith400_whenDisabled() {
        // api-key 已配为测试桩值，ensureConfigured 通过；记忆开关检查在模型调用之前同步抛 400
        assertThatThrownBy(() -> chatService.chat(new ChatRequest("你好", null, "")))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("会话记忆功能未启用");
    }

    @Test
    void resumeSessionPath_rejectedWith400_whenDisabled() {
        String sid = UUID.randomUUID().toString();
        assertThatThrownBy(() -> chatService.chat(new ChatRequest("追问", null, sid)))
                .isInstanceOf(InvalidChatRequestException.class)
                .hasMessageContaining("会话记忆功能未启用");
    }
}
