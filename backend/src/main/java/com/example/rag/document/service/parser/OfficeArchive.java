package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.service.upload.OfficeContentValidator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.*;

/** 只读取本地 ZIP 条目，禁用 XML 实体和远程关系解析。 */
final class OfficeArchive implements AutoCloseable {

    static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private final Path temporary;
    private final ZipFile zip;
    private final ParseBudget budget;

    /** 在预算内校验 Office 容器并打开临时压缩包，失败时清理临时文件。 */
    OfficeArchive(DocumentFormat format, InputStream input, ParseBudget budget) throws Exception {
        this.budget = budget;
        byte[] content = budget.read(input);
        new OfficeContentValidator(budget.documents()).validate(format, content, budget::check);
        temporary = Files.createTempFile("rag-parse-", ".zip");
        try {
            Files.write(temporary, content);
            zip = new ZipFile(temporary.toFile());
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
    }

    /** 检查 Office 包中是否包含指定内部部件。 */
    boolean has(String part) {
        return zip.getEntry(part) != null;
    }

    /** 安全打开包内 XML，拒绝实体与外部资源解析。 */
    XMLStreamReader xml(String part) throws Exception {
        budget.check();
        ZipEntry entry = zip.getEntry(part);
        if (entry == null || entry.isDirectory()) {
            throw ParseBudget.invalid();
        }
        byte[] bytes;
        try (InputStream input = zip.getInputStream(entry)) {
            bytes = input.readNBytes(budget.documents().maxZipEntryBytes() + 1);
        }
        budget.limit(bytes.length, budget.documents().maxZipEntryBytes());
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External resource disabled");
        });
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    /** 判断当前 XML 事件是否为指定命名空间的开始标签。 */
    static boolean start(XMLStreamReader xml, String namespace, String name) {
        return (
            xml.getEventType() == XMLStreamConstants.START_ELEMENT &&
            name.equals(xml.getLocalName()) &&
            namespace.equals(xml.getNamespaceURI())
        );
    }

    /** 判断当前 XML 事件是否为指定命名空间的结束标签。 */
    static boolean end(XMLStreamReader xml, String namespace, String name) {
        return (
            xml.getEventType() == XMLStreamConstants.END_ELEMENT &&
            name.equals(xml.getLocalName()) &&
            namespace.equals(xml.getNamespaceURI())
        );
    }

    /** 在预算内读取元素文本，拒绝实体引用。 */
    static String text(XMLStreamReader xml, ParseBudget budget) throws Exception {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (xml.hasNext() && depth > 0) {
            budget.check();
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                budget.limit((long) text.length() + xml.getTextLength(), budget.documents().maxTextCharacters());
                text.append(xml.getText());
            }
        }
        return text.toString();
    }

    /** 释放 Office 压缩包及其底层资源。 */
    public void close() throws Exception {
        try {
            zip.close();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
