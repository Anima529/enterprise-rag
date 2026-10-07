package com.example.rag.document.service.upload;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.upload.ValidatedUpload;
import com.example.rag.document.support.DocumentFixtures;
import com.example.rag.document.support.LegacyDocFixtures;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

class UploadValidatorTest {

    private UploadValidator validator = validator(DocumentFixtures.properties(10485760));

    /** 创建用例使用的上传资源限制与安全校验器。 */
    private UploadValidator validator(DocumentProperties properties) {
        return new UploadValidator(properties, new OfficeContentValidator(properties));
    }

    /** 构造指定文件名、MIME 和内容的 multipart 测试上传。 */
    private MockMultipartFile file(String name, String mime, byte[] content) {
        return new MockMultipartFile("file", name, mime, content);
    }

    /** 断言上传被拒绝且使用指定业务错误码。 */
    private void rejects(MockMultipartFile file, ErrorCode code) {
        assertThatThrownBy(() -> validator.validate(file)).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.errorCode()).isEqualTo(code)
        );
    }

    /** 验证支持的真实格式生成标准 MIME、摘要并兼容通用浏览器 MIME。 */
    @ParameterizedTest
    @EnumSource(DocumentFormat.class)
    void shouldAcceptSupportedRealFormatsWithCanonicalMimeHashAndGenericBrowserMime(DocumentFormat format)
        throws Exception {
        byte[] bytes = DocumentFixtures.content(format);
        ValidatedUpload result = validator.validate(
            file(" 文档." + format.extension().toUpperCase(), format.mime(), bytes)
        );
        assertThat(result.name()).isEqualTo("文档." + format.extension().toUpperCase());
        assertThat(result.format()).isEqualTo(format);
        assertThat(result.content()).isEqualTo(bytes);
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
        assertThat(
            validator.validate(file("doc." + format.extension(), "application/octet-stream", bytes)).sha256()
        ).isEqualTo(result.sha256());
    }

    /** 验证白名单之外的扩展名全部拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = { "a.html", "a.htm", "a.exe", "a.csv", "a.xls", "a", ".md" })
    void shouldRejectEveryExtensionOutsideAllowlist(String name) {
        rejects(file(name, "text/plain", new byte[] { 65 }), ErrorCode.DOC_415_UNSUPPORTED_FORMAT);
    }

    /** 验证路径、控制字符及误导性文件名被拒绝。 */
    @ParameterizedTest
    @ValueSource(
        strings = { "../a.txt", "C:\\temp\\a.md", "dir/a.txt", "a\r\n.txt", "a\u0000.txt", "a\u202Etxt.md", "a:txt.md" }
    )
    void shouldRejectPathsControlCharactersAndMisleadingFilenames(String name) {
        rejects(file(name, "text/plain", new byte[] { 65 }), ErrorCode.COMMON_400_VALIDATION);
    }

    /** 验证空白、非法 UTF-8 和伪装文本的二进制被拒绝。 */
    @Test
    void shouldRejectEmptyWhitespaceInvalidUtf8AndBinaryDisguisedAsText() {
        for (byte[] bytes : new byte[][] {
            {},
            " \r\n\t".getBytes(StandardCharsets.UTF_8),
            { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
            { (byte) 0xFF },
            { 65, 0, 66 },
            { 65, 27, 66 },
        }) {
            rejects(file("a.txt", "text/plain", bytes), ErrorCode.DOC_422_INVALID_CONTENT);
        }
        rejects(file("a.md", "text/plain; charset=GBK", new byte[] { 65 }), ErrorCode.DOC_422_INVALID_CONTENT);
    }

    /** 验证文件字节、正文字符和压缩包展开资源上限。 */
    @Test
    void shouldRespectByteCharacterAndArchiveExpansionLimits() throws Exception {
        validator = validator(DocumentFixtures.properties(8));
        rejects(file("a.txt", "text/plain", "123456789".getBytes(StandardCharsets.UTF_8)), ErrorCode.DOC_413_TOO_LARGE);
        DocumentProperties original = DocumentFixtures.properties(10485760);
        validator = validator(
            new DocumentProperties(
                original.maxBytes(),
                3,
                2,
                100,
                100,
                2,
                1,
                60,
                original.storageTimeout(),
                original.cleanupGrace(),
                original.cleanupInterval(),
                10
            )
        );
        rejects(file("a.txt", "text/plain", "abcd".getBytes(StandardCharsets.UTF_8)), ErrorCode.DOC_413_TOO_LARGE);
        rejects(
            file("a.docx", DocumentFormat.DOCX.mime(), DocumentFixtures.content(DocumentFormat.DOCX)),
            ErrorCode.DOC_413_TOO_LARGE
        );
    }

    /** 验证 MIME 不符、损坏 PDF/ZIP 及错误 Office 包被拒绝。 */
    @Test
    void shouldRejectMimeMismatchCorruptPdfZipAndWrongOfficePackage() throws Exception {
        rejects(
            file("a.pdf", "text/plain", DocumentFixtures.content(DocumentFormat.PDF)),
            ErrorCode.DOC_415_UNSUPPORTED_FORMAT
        );
        rejects(file("a.txt", "invalid", new byte[] { 65 }), ErrorCode.DOC_415_UNSUPPORTED_FORMAT);
        rejects(
            file("a.pdf", "application/pdf", "%PDF-1.7\nforged\n%%EOF".getBytes(StandardCharsets.UTF_8)),
            ErrorCode.DOC_422_INVALID_CONTENT
        );
        rejects(
            file("a.docx", DocumentFormat.DOCX.mime(), new byte[] { 80, 75, 3, 4 }),
            ErrorCode.DOC_422_INVALID_CONTENT
        );
        rejects(
            file("a.docx", DocumentFormat.DOCX.mime(), DocumentFixtures.content(DocumentFormat.XLSX)),
            ErrorCode.DOC_422_INVALID_CONTENT
        );
        byte[] valid = DocumentFixtures.content(DocumentFormat.DOCX);
        rejects(
            file("a.docx", DocumentFormat.DOCX.mime(), java.util.Arrays.copyOf(valid, valid.length - 22)),
            ErrorCode.DOC_422_INVALID_CONTENT
        );
    }

    /** 验证伪造、容器不符、截断、加密及宏 DOC 上传被拒绝。 */
    @Test
    void shouldRejectForgedWrongContainerTruncatedEncryptedAndMacroDocUploads() throws Exception {
        byte[] valid = LegacyDocFixtures.sample();
        for (byte[] bytes : new byte[][] {
            "{\\rtf1 not a Word binary file}".getBytes(StandardCharsets.UTF_8),
            DocumentFixtures.content(DocumentFormat.DOCX),
            LegacyDocFixtures.otherOleDocument(),
            java.util.Arrays.copyOf(valid, 1024),
            LegacyDocFixtures.encrypted(),
            LegacyDocFixtures.read("SimpleMacro.doc"),
        }) {
            rejects(file("a.doc", "application/msword", bytes), ErrorCode.DOC_422_INVALID_CONTENT);
        }
        rejects(file("a.doc", DocumentFormat.DOCX.mime(), valid), ErrorCode.DOC_415_UNSUPPORTED_FORMAT);
    }

    /** 验证 Office 路径越界、宏及 XML 外部实体被拒绝且不请求外链。 */
    @Test
    void shouldRejectOfficeTraversalMacrosAndXmlExternalEntitiesWithoutFetchingThem() throws Exception {
        for (String extra : new String[] { "../escape.xml", "word/vbaProject.bin" }) {
            Map<String, String> entries = DocumentFixtures.office(DocumentFormat.DOCX);
            entries.put(extra, "x");
            rejects(
                file("a.docx", DocumentFormat.DOCX.mime(), DocumentFixtures.zip(entries)),
                ErrorCode.DOC_422_INVALID_CONTENT
            );
        }
        Map<String, String> entries = DocumentFixtures.office(DocumentFormat.DOCX);
        entries.put(
            "word/document.xml",
            "<!DOCTYPE document [<!ENTITY x SYSTEM 'http://127.0.0.1:9/secret'>]><document>&x;</document>"
        );
        rejects(
            file("a.docx", DocumentFormat.DOCX.mime(), DocumentFixtures.zip(entries)),
            ErrorCode.DOC_422_INVALID_CONTENT
        );
    }

    /** 验证 Markdown HTML、链接和代码仅作为 UTF-8 数据。 */
    @Test
    void shouldTreatMarkdownHtmlLinksAndCodeAsInertUtf8Data() {
        byte[] content = "# 文档\n<script>alert(1)</script>\n![图](http://127.0.0.1:9/private)\n".getBytes(
            StandardCharsets.UTF_8
        );
        assertThat(validator.validate(file("a.md", "text/plain", content)).content()).isEqualTo(content);
    }
}
