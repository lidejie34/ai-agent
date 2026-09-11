package com.dj.ai.agentchat.rag.admin;

import lombok.Getter;

/**
 * 知识库管理端业务异常：携带稳定错误码，由控制器统一映射 HTTP 状态与 JSON 体。
 */
@Getter
public class KbAdminException extends RuntimeException {

    /** 文件扩展名/编码/内容不合法（400）。 */
    public static final String KB_INVALID_FILE = "KB_INVALID_FILE";
    /** 文件超过大小上限（400；容器层 MaxUploadSizeExceededException 也归并到此码）。 */
    public static final String KB_FILE_TOO_LARGE = "KB_FILE_TOO_LARGE";
    /** 文档不存在（404）。 */
    public static final String KB_NOT_FOUND = "KB_NOT_FOUND";
    /** RAG 开关关闭（503，由拦截器/控制器路径处理）。 */
    public static final String KB_DISABLED = "KB_DISABLED";
    /** Ollama embedding 失败（502；文档落 FAILED）。 */
    public static final String KB_EMBEDDING_FAILED = "KB_EMBEDDING_FAILED";
    /** PG 读写失败（502；尽力落 FAILED）。 */
    public static final String KB_STORE_FAILED = "KB_STORE_FAILED";

    private final String code;

    public KbAdminException(String code, String message) {
        super(message);
        this.code = code;
    }
}
