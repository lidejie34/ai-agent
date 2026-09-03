package com.dj.ai.agentchat.memory;

import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 会话存储抽象（迭代3）：在 Spring AI {@link ChatMemory}（add/get/clear 消息记忆）之上
 * 显式扩展<b>会话创建</b>能力，业务层面向该接口编排（便于 mock 与替换实现）。
 *
 * <p>实现：{@code MybatisChatMemory}（MyBatis-Plus + MySQL）。不挂载
 * {@code MessageChatMemoryAdvisor}——加载/落库时机由 ChatService 业务层显式编排
 * （实证 2：advisor 的「调用前写 user / 重订阅重复写 / complete 线程阻塞 JDBC」无法规避）。
 */
public interface ConversationStore extends ChatMemory {

    /**
     * 幂等创建会话行（INSERT IGNORE）：新建态生成 UUID 后调用；续接态兼作「未知 UUID 自愈
     * 补会话行」与 DB 可用性探测。重复调用无副作用、不抛唯一键冲突。
     */
    void createSessionIfAbsent(String sessionId);
}
