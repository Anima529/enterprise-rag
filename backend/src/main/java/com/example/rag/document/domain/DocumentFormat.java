package com.example.rag.document.domain;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import java.util.Locale;
import java.util.Set;

public enum DocumentFormat {
    PDF("application/pdf", Set.of("application/pdf")),
    DOC("application/msword", Set.of("application/msword")),
    DOCX(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ),
    XLSX(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ),
    TXT("text/plain", Set.of("text/plain")),
    MD("text/markdown", Set.of("text/plain", "text/markdown", "text/x-markdown"));

    private final String mime;
    private final Set<String> accepted;

    /** 登记该文档格式的标准 MIME 与可接受类型集合。 */
    DocumentFormat(String mime, Set<String> accepted) {
        this.mime = mime;
        this.accepted = accepted;
    }

    /** 返回该格式对外使用的标准 MIME 类型。 */
    public String mime() {
        return mime;
    }

    /** 返回该格式对应的小写文件扩展名。 */
    public String extension() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 检查声明的 MIME 是否在该格式允许的集合中。 */
    public boolean accepts(String mimeType) {
        // 浏览器上传常使用通用 MIME，因此仍必须校验文件内容。
        return "application/octet-stream".equals(mimeType) || accepted.contains(mimeType);
    }

    /** 从文件扩展名识别允许的文档格式。 */
    public static DocumentFormat fromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            throw new BusinessException(ErrorCode.DOC_415_UNSUPPORTED_FORMAT);
        }
        String extension = filename.substring(dot + 1).toUpperCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (format.name().equals(extension)) {
                return format;
            }
        }
        throw new BusinessException(ErrorCode.DOC_415_UNSUPPORTED_FORMAT);
    }
}
