package com.dj.ai.agentchat.tool.security;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 密钥脱敏器（插入迭代 G，AC-54）：工具结果回传模型前、审计 input_summary/error_message、
 * tool 帧 arguments 摘要统一过一遍，命中模式替换为 {@link #REDACTED}。
 *
 * <p>内置模式：方舟/ark API Key、Authorization 头（Bearer/Basic/Token 等任意 scheme）、
 * api_key/token/secret/password 赋值（大小写不敏感）；可经
 * {@code app.tools.redact-patterns} 增补正则（整段匹配直接替换为脱敏串，
 * 编译失败仅 WARN 跳过该条，不影响启动）。
 */
@Slf4j
public class SecretRedactor {

    /** 脱敏替换串。 */
    public static final String REDACTED = "***REDACTED***";

    /** 一条脱敏规则：pattern 命中后，keepPrefix=true 时保留第 1 捕获组（键名/头名）仅抹值。 */
    private record RedactionRule(Pattern pattern, boolean keepPrefix) {
        String apply(String input) {
            return keepPrefix
                    ? pattern.matcher(input).replaceAll("$1" + REDACTED)
                    : pattern.matcher(input).replaceAll(REDACTED);
        }
    }

    // 方舟/ark API Key：ark- 前缀 + 8 位以上 token 字符（整段抹掉）
    private static final RedactionRule ARK_KEY = new RedactionRule(
            Pattern.compile("ark-[A-Za-z0-9\\-_]{8,}"), false);
    // Authorization 头：保留头名与认证 scheme（Bearer/Basic/Token/...），仅抹掉凭证值
    private static final RedactionRule AUTHORIZATION = new RedactionRule(
            Pattern.compile("(?i)(Authorization\\s*:\\s*(?:[A-Za-z]+\\s+)?)[A-Za-z0-9\\-._~+/]+=*"),
            true);
    // 通用密钥赋值：api_key= / token: / secret= / password: 等（大小写/分隔符不敏感）
    private static final RedactionRule KEY_ASSIGNMENT = new RedactionRule(
            Pattern.compile("(?i)((?:api[_-]?key|token|secret|password)\\s*[=:]\\s*)\\S+"),
            true);

    private final List<RedactionRule> rules;

    public SecretRedactor(List<String> extraPatterns) {
        List<RedactionRule> compiled = new ArrayList<>();
        compiled.add(ARK_KEY);
        compiled.add(AUTHORIZATION);
        compiled.add(KEY_ASSIGNMENT);
        if (extraPatterns != null) {
            for (String regex : extraPatterns) {
                try {
                    // 增补正则整段命中即抹掉（不支持捕获组保留前缀）
                    compiled.add(new RedactionRule(Pattern.compile(regex), false));
                } catch (Exception e) {
                    log.warn("增补脱敏正则编译失败，跳过: {}, 原因={}", regex, e.getMessage());
                }
            }
        }
        this.rules = List.copyOf(compiled);
    }

    /**
     * 脱敏；null 入参返回 null。
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (RedactionRule rule : rules) {
            out = rule.apply(out);
        }
        return out;
    }
}
