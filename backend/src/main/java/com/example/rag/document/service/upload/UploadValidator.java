package com.example.rag.document.service.upload;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.upload.ValidatedUpload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.hwpf.HWPFDocument;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class UploadValidator {

    private static final int PDF_HEADER_SCAN_BYTES = 8;

    private static final int MAX_FILENAME_CHARACTERS = 255;
    private static final int PDF_TAIL_SCAN_BYTES = 1024;

    private final DocumentProperties properties;
    private final OfficeContentValidator office;

    /** 配置允许格式、资源上限及 Office 安全校验依赖。 */
    public UploadValidator(DocumentProperties properties, OfficeContentValidator office) {
        this.properties = properties;
        this.office = office;
    }

    /** 按文件名、格式、MIME、内容和资源上限校验上传，生成标准元数据与摘要。 */
    public ValidatedUpload validate(MultipartFile file) {
        String name = normalizeFilename(file.getOriginalFilename());
        DocumentFormat format = DocumentFormat.fromFilename(name);
        validateMime(format, file.getContentType());
        if (file.getSize() > properties.maxBytes()) {
            fail(ErrorCode.DOC_413_TOO_LARGE);
        }
        byte[] content;
        try (InputStream stream = file.getInputStream()) {
            content = stream.readNBytes(properties.maxBytes() + 1);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.DOC_422_INVALID_CONTENT);
        }
        if (content.length > properties.maxBytes()) {
            fail(ErrorCode.DOC_413_TOO_LARGE);
        }
        if (content.length == 0) {
            fail(ErrorCode.DOC_422_INVALID_CONTENT);
        }
        switch (format) {
            case TXT, MD -> validateText(content);
            case PDF -> validatePdf(content);
            case DOC -> validateDoc(content);
            case DOCX, XLSX -> office.validate(format, content);
        }
        return new ValidatedUpload(name, format, content, sha256(content));
    }

    /** 规范化文件名并拒绝路径、控制字符和误导性名称。 */
    static String normalizeFilename(String original) {
        if (original == null) {
            fail(ErrorCode.COMMON_400_VALIDATION);
        }
        String name = Normalizer.normalize(original, Normalizer.Form.NFC).strip();
        // 文件名只作为元数据；拒绝客户端路径，防止路径穿越被默默接受。
        if (
            name.isBlank() ||
            name.length() > MAX_FILENAME_CHARACTERS ||
            name.equals(".") ||
            name.equals("..") ||
            name
                .codePoints()
                .anyMatch(
                    c ->
                        Character.isISOControl(c) ||
                        c == '/' ||
                        c == '\\' ||
                        c == ':' ||
                        c == '<' ||
                        c == '>' ||
                        c == '"' ||
                        c == '|' ||
                        c == '?' ||
                        c == '*' ||
                        Character.getType(c) == Character.FORMAT
                )
        ) {
            fail(ErrorCode.COMMON_400_VALIDATION);
        }
        return name;
    }

    /** 检查浏览器声明的 MIME 与允许格式是否一致。 */
    private void validateMime(DocumentFormat format, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            MediaType type = MediaType.parseMediaType(value);
            String mime = (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
            if (!format.accepts(mime)) {
                fail(ErrorCode.DOC_415_UNSUPPORTED_FORMAT);
            }
            if (
                (format == DocumentFormat.TXT || format == DocumentFormat.MD) &&
                type.getCharset() != null &&
                !type.getCharset().equals(StandardCharsets.UTF_8)
            ) {
                fail(ErrorCode.DOC_422_INVALID_CONTENT);
            }
        } catch (InvalidMediaTypeException exception) {
            fail(ErrorCode.DOC_415_UNSUPPORTED_FORMAT);
        }
    }

    /** 严格检查 UTF-8 文本、有效正文及字符数量。 */
    private void validateText(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            if (text.length() > properties.maxTextCharacters()) {
                fail(ErrorCode.DOC_413_TOO_LARGE);
            }
            if (
                text.isBlank() ||
                text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
            ) {
                fail(ErrorCode.DOC_422_INVALID_CONTENT);
            }
        } catch (CharacterCodingException exception) {
            fail(ErrorCode.DOC_422_INVALID_CONTENT);
        }
    }

    /** 检查 PDF 可打开且未加密，拒绝损坏内容。 */
    private void validatePdf(byte[] content) {
        String header = new String(
            content,
            0,
            Math.min(content.length, PDF_HEADER_SCAN_BYTES),
            StandardCharsets.US_ASCII
        );
        String tail = new String(
            content,
            Math.max(0, content.length - PDF_TAIL_SCAN_BYTES),
            Math.min(content.length, PDF_TAIL_SCAN_BYTES),
            StandardCharsets.US_ASCII
        );
        if (!header.matches("%PDF-[12]\\.[0-9].*") || !tail.contains("%%EOF")) {
            fail(ErrorCode.DOC_422_INVALID_CONTENT);
        }
        try (PDDocument pdf = Loader.loadPDF(content)) {
            if (pdf.isEncrypted() || pdf.getNumberOfPages() == 0) {
                fail(ErrorCode.DOC_422_INVALID_CONTENT);
            }
            if (pdf.getNumberOfPages() > properties.maxPdfPages()) {
                fail(ErrorCode.DOC_413_TOO_LARGE);
            }
        } catch (IOException exception) {
            fail(ErrorCode.DOC_422_INVALID_CONTENT);
        }
    }

    /** 校验旧版 Word 二进制容器及可见内容。 */
    private void validateDoc(byte[] content) {
        try (HWPFDocument document = LegacyWordContent.open(content, properties, () -> {})) {
            document.getRange();
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            fail(ErrorCode.DOC_422_INVALID_CONTENT);
        }
    }

    /** 计算上传内容摘要以支持同知识库去重。 */
    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 按指定错误码中断上传校验。 */
    private static void fail(ErrorCode code) {
        throw new BusinessException(code);
    }
}
