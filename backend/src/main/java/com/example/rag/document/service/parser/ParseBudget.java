package com.example.rag.document.service.parser;

import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.config.parser.ParserProperties;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.support.parser.SourceMetadata;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 通过协作式截止时间和确定的字节、字符、结构上限限制解析资源。 */
public final class ParseBudget {

    private static final int INPUT_BUFFER_BYTES = 8192;

    private final ParserProperties properties;
    private final DocumentProperties documents;
    private final long deadline;
    private int characters;
    private long metadataCharacters;
    private final List<ParsedBlock> blocks = new ArrayList<>();

    /** 建立解析资源计数与共享截止时间。 */
    public ParseBudget(ParserProperties properties, DocumentProperties documents) {
        this(properties, documents, System.nanoTime() + properties.timeout().toNanos());
    }

    /** 建立解析资源计数与共享截止时间。 */
    private ParseBudget(ParserProperties properties, DocumentProperties documents, long deadline) {
        this.properties = properties;
        this.documents = documents;
        this.deadline = deadline;
    }

    /** 为归一化输出创建独立计数预算，沿用原解析截止时间。 */
    public ParseBudget outputBudget() {
        return new ParseBudget(properties, documents, deadline);
    }

    /** 返回解析结构与资源限制配置。 */
    public ParserProperties properties() {
        return properties;
    }

    /** 返回原文件字节数和输出字符量限制。 */
    public DocumentProperties documents() {
        return documents;
    }

    /** 检查线程中断与截止时间，及时终止失效解析。 */
    public void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new LostTaskLease();
        }
        if (System.nanoTime() - deadline >= 0) {
            throw new IndexFailure(ErrorCode.DOC_504_PARSE_TIMEOUT.name(), "文档解析超时，请减少文件规模后重试", false);
        }
    }

    /** 检查时间并拒绝超过给定资源上限的计数。 */
    public void limit(long value, long maximum) {
        check();
        if (value > maximum) {
            throw new IndexFailure(
                ErrorCode.DOC_413_PARSE_LIMIT.name(),
                "文档解析超过字符、页数、块数或工作表资源上限",
                false
            );
        }
    }

    /** 在文件字节预算内完整读取输入流。 */
    public byte[] read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[INPUT_BUFFER_BYTES];
        int size;
        while ((size = input.read(buffer)) != -1) {
            limit((long) output.size() + size, documents.maxBytes());
            output.write(buffer, 0, size);
        }
        return output.toByteArray();
    }

    /** 校验块内容及累计正文、元数据和块数量后保存产物。 */
    public void add(ParsedBlock block) {
        check();
        if (
            block.text().isBlank() && !ParsedBlockProtocol.TABLE_CELL.equals(block.metadata().get(SourceMetadata.KIND))
        ) {
            return;
        }
        if (block.text().indexOf('\0') >= 0) {
            throw invalid();
        }
        limit((long) characters + block.text().length(), documents.maxTextCharacters());
        // 标题路径会在每个块中重复；JSON 序列化前限制其累计大小。
        long metadataSize = size(block.metadata()) + size(block.chapter()) + size(block.section());
        limit(metadataCharacters + metadataSize, documents.maxTextCharacters());
        limit(blocks.size() + 1L, properties.maxBlocks());
        characters += block.text().length();
        metadataCharacters += metadataSize;
        blocks.add(block);
    }

    /** 检查尚未输出的缓冲字符是否会超过预算。 */
    public void pendingCharacters(long count) {
        limit((long) characters + count, documents.maxTextCharacters());
    }

    /** 返回已保存解析块的不可变快照。 */
    public List<ParsedBlock> blocks() {
        return List.copyOf(blocks);
    }

    /** 估算嵌套元数据字符量，防止标题路径等重复放大输出。 */
    private long size(Object value) {
        check();
        if (value == null) {
            return 0;
        }
        if (value instanceof String text) {
            return text.length();
        }
        long count = 0;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                count += size(entry.getKey()) + size(entry.getValue());
                limit(count, documents.maxTextCharacters());
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                count += size(item);
                limit(count, documents.maxTextCharacters());
            }
        } else {
            count = value.toString().length();
        }
        return count;
    }

    /** 构造损坏、加密或无法解析文档的统一阶段错误。 */
    public static IndexFailure invalid() {
        return new IndexFailure(ErrorCode.DOC_422_PARSE_FAILED.name(), "文档损坏、加密或结构无法解析", false);
    }
}
