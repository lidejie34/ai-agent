package com.dj.ai.agentchat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具查证证据随轮持久化配置（迭代8，{@code app.chat.evidence}）。
 * 总开关默认关闭（配置级回滚路径）；关闭时行为与上一迭代逐字节一致。
 */
@ConfigurationProperties(prefix = "app.chat.evidence")
public class ChatEvidenceProperties {

    /** 证据能力总开关，默认关闭（生产/QA 灰度用环境变量 APP_CHAT_EVIDENCE_ENABLED 开启）。 */
    private boolean enabled = false;

    /** 单条工具结果摘录上限（字符）。 */
    private int maxCharsPerCall = 800;

    /** 单轮证据消息总长度硬顶（字符）。 */
    private int maxCharsPerTurn = 4000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxCharsPerCall() {
        return maxCharsPerCall;
    }

    public void setMaxCharsPerCall(int maxCharsPerCall) {
        this.maxCharsPerCall = maxCharsPerCall;
    }

    public int getMaxCharsPerTurn() {
        return maxCharsPerTurn;
    }

    public void setMaxCharsPerTurn(int maxCharsPerTurn) {
        this.maxCharsPerTurn = maxCharsPerTurn;
    }
}
