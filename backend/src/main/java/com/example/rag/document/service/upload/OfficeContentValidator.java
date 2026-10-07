package com.example.rag.document.service.upload;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/** 仅执行有资源限制的包结构校验，不在此提取正文或访问外部资源。 */
@Component
public class OfficeContentValidator {

    private static final String CONTENT_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String RELATIONSHIPS_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private final DocumentProperties properties;

    /** 配置压缩包字节与展开资源校验上限。 */
    public OfficeContentValidator(DocumentProperties properties) {
        this.properties = properties;
    }

    /** 校验 Office 容器格式、展开资源和安全结构。 */
    public void validate(DocumentFormat format, byte[] bytes) {
        validate(format, bytes, () -> {});
    }

    /** 校验 Office 容器格式、展开资源和安全结构。 */
    public void validate(DocumentFormat format, byte[] bytes, Runnable check) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("rag-upload-", ".zip");
            Files.write(temporary, bytes);
            try (ZipFile zip = new ZipFile(temporary.toFile())) {
                validatePackage(zip, format, check);
            }
        } catch (IOException | XMLStreamException exception) {
            throw new BusinessException(ErrorCode.DOC_422_INVALID_CONTENT);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
                }
            }
        }
    }

    /** 检查 Office 必需部件、内容类型及内部关系。 */
    private void validatePackage(ZipFile zip, DocumentFormat format, Runnable check)
        throws IOException, XMLStreamException {
        Set<String> names = new HashSet<>();
        long expanded = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            check.run();
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (
                !names.add(name) ||
                name.startsWith("/") ||
                name.contains("\\") ||
                java.util.Arrays.stream(name.split("/")).anyMatch(part -> part.equals("..") || part.equals(".")) ||
                name.contains(":")
            ) {
                invalid();
            }
            if (names.size() > properties.maxZipEntries()) {
                tooLarge();
            }
            if (entry.isDirectory()) {
                continue;
            }
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith("vbaproject.bin")) {
                invalid();
            }
            if (entry.getSize() < 0 || entry.getCompressedSize() < 0) {
                invalid();
            }
            if (
                entry.getSize() > properties.maxZipEntryBytes() ||
                entry.getSize() > Math.max(1, entry.getCompressedSize()) * properties.maxZipCompressionRatio()
            ) {
                tooLarge();
            }
            expanded += entry.getSize();
            if (expanded > properties.maxZipExpandedBytes()) {
                tooLarge();
            }
            byte[] content;
            try (InputStream stream = zip.getInputStream(entry)) {
                content = stream.readNBytes(properties.maxZipEntryBytes() + 1);
            }
            if (content.length > properties.maxZipEntryBytes()) {
                tooLarge();
            }
            CRC32 checksum = new CRC32();
            checksum.update(content);
            if (content.length != entry.getSize() || checksum.getValue() != entry.getCrc()) {
                invalid();
            }
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                xml(content, null, null, check);
            }
        }
        String main = format == DocumentFormat.DOCX ? "word/document.xml" : "xl/workbook.xml";
        String namespace =
            "http://schemas.openxmlformats.org/" +
            (format == DocumentFormat.DOCX ? "wordprocessingml/2006/main" : "spreadsheetml/2006/main");
        xml(read(zip, main), format == DocumentFormat.DOCX ? "document" : "workbook", namespace);
        if (
            format == DocumentFormat.XLSX &&
            names.stream().noneMatch(n -> n.startsWith("xl/worksheets/") && n.endsWith(".xml"))
        ) {
            invalid();
        }
        validateManifest(read(zip, "[Content_Types].xml"), main, format);
        validateRelationships(read(zip, "_rels/.rels"), main);
    }

    /** 限制单个压缩部件展开大小并检查解析预算。 */
    private byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            invalid();
        }
        try (InputStream stream = zip.getInputStream(entry)) {
            return stream.readNBytes(properties.maxZipEntryBytes() + 1);
        }
    }

    /** 验证内容类型清单，拒绝宏或不匹配的文档类型。 */
    private void validateManifest(byte[] bytes, String main, DocumentFormat format) throws XMLStreamException {
        xml(bytes, "Types", CONTENT_TYPES_NS);
        String expected =
            format == DocumentFormat.DOCX
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
        XMLStreamReader reader = reader(bytes);
        boolean found = false;
        try {
            while (reader.hasNext()) {
                if (
                    reader.next() == XMLStreamConstants.START_ELEMENT &&
                    "Override".equals(reader.getLocalName()) &&
                    CONTENT_TYPES_NS.equals(reader.getNamespaceURI()) &&
                    ("/" + main).equals(reader.getAttributeValue(null, "PartName"))
                ) {
                    if (!expected.equals(reader.getAttributeValue(null, "ContentType"))) {
                        invalid();
                    }
                    found = true;
                }
            }
        } finally {
            reader.close();
        }
        if (!found) {
            invalid();
        }
    }

    /** 校验关系结构和目标，拒绝危险内部路径。 */
    private void validateRelationships(byte[] bytes, String main) throws XMLStreamException {
        xml(bytes, "Relationships", RELATIONSHIPS_NS);
        XMLStreamReader reader = reader(bytes);
        boolean found = false;
        try {
            while (reader.hasNext()) {
                if (
                    reader.next() == XMLStreamConstants.START_ELEMENT &&
                    "Relationship".equals(reader.getLocalName()) &&
                    RELATIONSHIPS_NS.equals(reader.getNamespaceURI()) &&
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument".equals(
                        reader.getAttributeValue(null, "Type")
                    )
                ) {
                    String target = reader.getAttributeValue(null, "Target");
                    if (
                        (!main.equals(target) && !("/" + main).equals(target)) ||
                        "External".equals(reader.getAttributeValue(null, "TargetMode"))
                    ) {
                        invalid();
                    }
                    found = true;
                }
            }
        } finally {
            reader.close();
        }
        if (!found) {
            invalid();
        }
    }

    /** 在安全 XML 设置下读取部件，拒绝外部实体。 */
    private void xml(byte[] bytes, String expectedRoot, String expectedNamespace) throws XMLStreamException {
        xml(bytes, expectedRoot, expectedNamespace, () -> {});
    }

    /** 在安全 XML 设置下读取部件，拒绝外部实体。 */
    private void xml(byte[] bytes, String expectedRoot, String expectedNamespace, Runnable check)
        throws XMLStreamException {
        XMLStreamReader reader = reader(bytes);
        boolean root = false;
        try {
            while (reader.hasNext()) {
                check.run();
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    invalid();
                }
                if (event == XMLStreamConstants.START_ELEMENT && !root) {
                    root = true;
                    if (
                        expectedRoot != null &&
                        (!expectedRoot.equals(reader.getLocalName()) ||
                            !expectedNamespace.equals(reader.getNamespaceURI()))
                    ) {
                        invalid();
                    }
                }
            }
        } finally {
            reader.close();
        }
        if (!root) {
            invalid();
        }
    }

    /** 创建禁止 DTD 和外部实体的 XML 读取器。 */
    private XMLStreamReader reader(byte[] bytes) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External resources are disabled");
        });
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    /** 构造 Office 内容无效的统一上传错误。 */
    private void invalid() {
        throw new BusinessException(ErrorCode.DOC_422_INVALID_CONTENT);
    }

    /** 构造压缩包展开资源超限的统一上传错误。 */
    private void tooLarge() {
        throw new BusinessException(ErrorCode.DOC_413_TOO_LARGE);
    }
}
