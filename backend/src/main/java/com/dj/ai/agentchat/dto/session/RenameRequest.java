package com.dj.ai.agentchat.dto.session;

/**
 * 重命名请求体（迭代4 FR-8）：{@code {"title":"..."}}；
 * 服务端 trim 后校验 1–200 字符（code point 计），空白/超长 400。
 *
 * @param title 新标题
 */
public record RenameRequest(String title) {
}
