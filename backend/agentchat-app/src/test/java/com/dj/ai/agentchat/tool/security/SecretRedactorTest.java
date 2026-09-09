package com.dj.ai.agentchat.tool.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4：密钥脱敏（AC-54）——ark key、Authorization/Bearer、api_key/token/secret/password
 * 赋值（大小写/分隔符变体）→ ***REDACTED***；增补正则生效、非法正则跳过不炸。
 */
class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor(List.of());

    @Test
    void redact_arkApiKey() {
        String out = redactor.redact("使用密钥 ark-abcdefgh12345678XYZ 调用了接口");
        assertThat(out).contains(SecretRedactor.REDACTED);
        assertThat(out).doesNotContain("ark-abcdefgh12345678XYZ");
    }

    @Test
    void redact_authorizationBearerHeader() {
        String out = redactor.redact("Authorization: Bearer abc.def.ghi-jkl_123/456==");
        assertThat(out).contains(SecretRedactor.REDACTED);
        assertThat(out).doesNotContain("abc.def.ghi");
        // 头名保留，便于模型理解上下文
        assertThat(out).contains("Authorization");
    }

    @Test
    void redact_authorizationHeader_lowercaseAndNoBearer() {
        String out = redactor.redact("authorization: Basic dXNlcjpwYXNz");
        assertThat(out).contains(SecretRedactor.REDACTED);
        assertThat(out).doesNotContain("dXNlcjpwYXNz");
    }

    @Test
    void redact_keyAssignments_allVariants() {
        assertThat(redactor.redact("api_key=ak-1234567890abcdef")).contains(SecretRedactor.REDACTED);
        assertThat(redactor.redact("api-key: 9f8e7d6c5b4a")).contains(SecretRedactor.REDACTED);
        assertThat(redactor.redact("token=deadbeefcafebabe")).contains(SecretRedactor.REDACTED);
        assertThat(redactor.redact("SECRET: s3cr3t-value")).contains(SecretRedactor.REDACTED);
        assertThat(redactor.redact("password = p@ssw0rd!")).contains(SecretRedactor.REDACTED);
        // 值被抹掉
        assertThat(redactor.redact("token=deadbeefcafebabe")).doesNotContain("deadbeefcafebabe");
    }

    @Test
    void redact_multipleSecretsInOneText_allReplaced() {
        String out = redactor.redact("k1=ark-aaaaaaaa11111111 and Authorization: Bearer zzz.yyy.xxx");
        // REDACTED 含 '*' 正则元字符，split 前需 quote
        assertThat(out.split(java.util.regex.Pattern.quote(SecretRedactor.REDACTED), -1))
                .hasSize(3); // 两处替换
        assertThat(out).doesNotContain("ark-aaaaaaaa11111111").doesNotContain("zzz.yyy.xxx");
    }

    @Test
    void redact_extraPatterns_fromConfig() {
        SecretRedactor withExtra = new SecretRedactor(List.of("CUSTOM-SECRET-\\d+"));
        String out = withExtra.redact("trace CUSTOM-SECRET-42 happened");
        assertThat(out).contains(SecretRedactor.REDACTED);
        assertThat(out).doesNotContain("CUSTOM-SECRET-42");
    }

    @Test
    void redact_invalidExtraPattern_isSkippedWithoutThrowing() {
        SecretRedactor withBad = new SecretRedactor(List.of("[bad(regex"));
        // 内置模式仍生效
        assertThat(withBad.redact("ark-abcd1234efgh5678")).contains(SecretRedactor.REDACTED);
    }

    @Test
    void redact_nullAndEmpty_nullSafe() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
        assertThat(redactor.redact("普通日志，无密钥")).isEqualTo("普通日志，无密钥");
    }
}
