package com.dj.ai.agentchat.rag.admin.service;

import com.dj.ai.agentchat.rag.RagProperties;
import com.dj.ai.agentchat.rag.admin.KbAdminException;
import com.dj.ai.agentchat.rag.chunk.TextChunker;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingException;
import com.dj.ai.agentchat.rag.embed.RagEmbeddingService;
import com.dj.ai.agentchat.rag.schema.RagSchemaInitializer;
import com.dj.ai.agentchat.rag.store.KbRepository;
import com.dj.ai.agentchat.rag.store.RagDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_EMBEDDING_FAILED;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_INVALID_FILE;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_NOT_FOUND;
import static com.dj.ai.agentchat.rag.admin.KbAdminException.KB_STORE_FAILED;

/**
 * 知识库文档编排（迭代6）：校验 → UTF-8 严格解码 → 切片 → 批量 embedding → 事务落库；
 * 同名覆盖由 {@link KbRepository#saveReady} 在单事务内完成；外部依赖失败时落 FAILED 行
 * （保留原文，reindex 可重试）并抛 {@link KbAdminException}（F2/F6/F10）。
 */
@Slf4j
public class KbDocumentService {

    private final KbRepository repository;
    private final RagEmbeddingService embeddingService;
    private final TextChunker chunker;
    private final RagProperties properties;
    private final RagSchemaInitializer schemaInitializer;

    public KbDocumentService(KbRepository repository,
                             RagEmbeddingService embeddingService,
                             TextChunker chunker,
                             RagProperties properties,
                             RagSchemaInitializer schemaInitializer) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.chunker = chunker;
        this.properties = properties;
        this.schemaInitializer = schemaInitializer;
    }

    /** 上传并同步完成切片+向量化；成功返回 READY 文档视图（不含原文）。 */
    public RagDocument upload(String originalFileName, byte[] bytes) {
        schemaInitializer.ensureSchema();
        String fileName = sanitizeFileName(originalFileName);
        validateExtension(fileName);
        validateSize(bytes);
        String content = decodeUtf8(bytes, fileName);
        if (content.isBlank()) {
            throw invalid("文件内容为空：" + fileName);
        }
        List<String> chunks = chunker.chunk(content);
        if (chunks.isEmpty()) {
            throw invalid("切片结果为空（文件无有效文本）：" + fileName);
        }
        int maxChunks = properties.getUpload().getMaxChunks();
        if (chunks.size() > maxChunks) {
            throw invalid("切片数 " + chunks.size() + " 超过上限 " + maxChunks
                    + "，请将文档拆小后再上传：" + fileName);
        }
        String hash = sha1Hex(bytes);

        List<float[]> vectors = embedOrFail(fileName, bytes.length, content, hash, chunks);
        try {
            long id = repository.saveReady(fileName, bytes.length, content, hash, chunks, vectors);
            log.info("RAG 文档入库成功: {}（{} 片）", fileName, chunks.size());
            return repository.findById(id)
                    .orElseThrow(() -> new KbAdminException(KB_STORE_FAILED,
                            "文档插入后回读失败: " + fileName))
                    .withoutContent();
        } catch (DataAccessException e) {
            log.error("RAG 文档落库失败: {}", fileName, e);
            saveFailedRow(fileName, bytes.length, content, hash, e.getMessage());
            throw new KbAdminException(KB_STORE_FAILED, "知识库写入失败: " + e.getMessage());
        }
    }

    /** 文档列表（不含原文）。 */
    public List<RagDocument> list() {
        schemaInitializer.ensureSchema();
        return repository.listDocuments();
    }

    /** 删除文档（FK 级联片段）；不存在 → KB_NOT_FOUND。 */
    public void delete(long id) {
        schemaInitializer.ensureSchema();
        requireExists(id);
        repository.deleteById(id);
    }

    /** 用已存原文重建切片与向量（F5）；失败落 FAILED 并抛错。 */
    public RagDocument reindex(long id) {
        schemaInitializer.ensureSchema();
        RagDocument doc = requireExists(id);
        List<String> chunks = chunker.chunk(doc.content());
        if (chunks.isEmpty()) {
            throw new KbAdminException(KB_INVALID_FILE, "原文无有效文本，无法重建: " + doc.fileName());
        }
        if (chunks.size() > properties.getUpload().getMaxChunks()) {
            throw new KbAdminException(KB_INVALID_FILE, "切片数 " + chunks.size()
                    + " 超过上限，无法重建: " + doc.fileName());
        }
        List<float[]> vectors;
        try {
            vectors = embeddingService.embedBatch(chunks);
        } catch (RagEmbeddingException e) {
            repository.markFailed(id, e.getMessage());
            throw new KbAdminException(KB_EMBEDDING_FAILED, "重建向量化失败: " + e.getMessage());
        }
        try {
            repository.replaceChunks(id, doc.fileName(), chunks.size(), chunks, vectors);
            log.info("RAG 文档重建成功: {}（{} 片）", doc.fileName(), chunks.size());
        } catch (DataAccessException e) {
            repository.markFailed(id, e.getMessage());
            throw new KbAdminException(KB_STORE_FAILED, "重建落库失败: " + e.getMessage());
        }
        return requireExists(id).withoutContent();
    }

    private RagDocument requireExists(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new KbAdminException(KB_NOT_FOUND, "文档不存在: id=" + id));
    }

    private List<float[]> embedOrFail(String fileName, int sizeBytes, String content,
                                      String hash, List<String> chunks) {
        try {
            return embeddingService.embedBatch(chunks);
        } catch (RagEmbeddingException e) {
            saveFailedRow(fileName, sizeBytes, content, hash, e.getMessage());
            throw new KbAdminException(KB_EMBEDDING_FAILED, "向量化失败: " + e.getMessage());
        }
    }

    /** best-effort 失败落库：再失败只 warn（不能掩盖原始异常）。 */
    private void saveFailedRow(String fileName, int sizeBytes, String content,
                               String hash, String error) {
        try {
            repository.saveFailed(fileName, sizeBytes, content, hash, error);
        } catch (DataAccessException ex) {
            log.warn("RAG FAILED 行落库也失败: {} - {}", fileName, ex.getMessage());
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("文件名为空");
        }
        String trimmed = name.strip();
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw invalid("文件名不允许包含路径分隔符: " + trimmed);
        }
        if (trimmed.length() > 255) {
            throw invalid("文件名过长（>255 字符）");
        }
        return trimmed;
    }

    private void validateExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot <= 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!properties.getUpload().getAllowedExt().contains(ext)) {
            throw invalid("仅支持 " + String.join("/", properties.getUpload().getAllowedExt())
                    + "（UTF-8）文件，收到: " + (ext.isEmpty() ? "无扩展名" : "." + ext));
        }
    }

    private void validateSize(byte[] bytes) {
        long max = properties.getUpload().getMaxFileBytes();
        if (bytes == null || bytes.length == 0) {
            throw invalid("文件为空（0 字节）");
        }
        if (bytes.length > max) {
            throw new KbAdminException(KbAdminException.KB_FILE_TOO_LARGE,
                    "文件过大: " + bytes.length + " 字节，上限 " + max + " 字节（10MB）");
        }
    }

    /** 严格 UTF-8 解码：非法字节序列直接 400（F1），不做替换字符静默污染。 */
    private String decodeUtf8(byte[] bytes, String fileName) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("文件不是合法的 UTF-8 文本（另存为 UTF-8 后重试）: " + fileName);
        }
    }

    private static KbAdminException invalid(String message) {
        return new KbAdminException(KB_INVALID_FILE, message);
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
