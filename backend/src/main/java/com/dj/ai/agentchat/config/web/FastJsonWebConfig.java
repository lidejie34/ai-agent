package com.dj.ai.agentchat.config.web;

import com.alibaba.fastjson2.support.config.FastJsonConfig;
import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JSON 序列化/反序列化统一走 fastjson2（迭代3）。
 *
 * <p>经 {@link WebMvcConfigurer#extendMessageConverters} 把
 * {@link FastJsonHttpMessageConverter} 插到转换器列表<b>最前</b>（实证 6.4）：
 * {@code @RequestBody}（canRead application/json）、{@code @ResponseBody}、
 * SSE JSON 帧（SseEmitter 与 @ResponseBody 共用同一份转换器列表，按序 canWrite）
 * 全部优先由 fastjson2 处理；Jackson 转换器保留在列表中兜底，不移除。
 *
 * <p>特性保持 fastjson2 默认（实证 6.2/6.3）：reader 默认即支持 record 三态反序列化
 * （缺省/显式 null → null、空串 → ""）；writer 默认省略 null 字段（无状态响应体与
 * 迭代1/2 线格式一致，不出现 {@code "sessionId":null} 噪音键），故<b>不</b>开启
 * WriteMapNullValue。
 *
 * <p><b>顺序红线（迭代4 冒烟实证）</b>：fastjson2 转换器构造为 {@code super(MediaType.ALL)}，
 * 支持 {@code *&zwj;/*}，若直接置于列表 0 位，会连 SSE 的纯文本片段（{@code event:xxx\n}、
 * {@code \n\n} 分帧、{@code event:done} 的 {@code [DONE]}、{@code :keepalive} 注释帧）
 * 也当作 JSON 字符串序列化（帧体外加引号、换行转义为字面量 {@code \n}），前端 SSE 分帧
 * 直接失效。故必须把框架既有的 {@link StringHttpMessageConverter} 移到 fastjson2 <b>之前</b>：
 * String/文本片段走原始输出，DTO 对象 String 转换器 {@code supports(String.class)} 不命中、
 * 回落 fastjson2 输出 JSON，两者各得其所。
 */
@Configuration
public class FastJsonWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        FastJsonConfig fastJsonConfig = new FastJsonConfig();
        fastJsonConfig.setCharset(StandardCharsets.UTF_8);
        // readerFeatures / writerFeatures 留空 = fastjson2 默认特性（null 省略、record 可绑定）

        FastJsonHttpMessageConverter fastJsonConverter = new FastJsonHttpMessageConverter();
        fastJsonConverter.setFastJsonConfig(fastJsonConfig);
        converters.add(0, fastJsonConverter);

        // StringHttpMessageConverter 必须位于 fastjson2 之前：SSE 纯文本帧/[DONE] 原样输出，
        // 不被 MediaType.ALL 的 fastjson2 当 JSON 字符串加引号转义（DTO 对象不受影响，见类注释）。
        converters.stream()
                .filter(converter -> converter instanceof StringHttpMessageConverter)
                .findFirst()
                .ifPresent(stringConverter -> {
                    converters.remove(stringConverter);
                    converters.add(0, stringConverter);
                });
    }
}
