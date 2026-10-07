package com.example.rag.document.support;

import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class DocumentFixtures {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private DocumentFixtures() {
    }

    /** 按文档格式生成可通过真实上传校验的测试内容。 */
    public static byte[] content(DocumentFormat format) throws Exception {
        if (format == DocumentFormat.DOC) {
            return LegacyDocFixtures.sample();
        }
        if (format == DocumentFormat.PDF) {
            try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText("Phase 4 PDF upload sample");
                    stream.endText();
                }
                pdf.save(out);
                return out.toByteArray();
            }
        }
        if (format == DocumentFormat.DOCX || format == DocumentFormat.XLSX) {
            return zip(office(format));
        }
        if (format == DocumentFormat.TXT) {
            return "\uFEFF中文 TXT 上传样例\r\n\r\n原文件按 UTF-8 保存。\n".getBytes(StandardCharsets.UTF_8);
        }
        return "\uFEFF# 中文知识库\r\n\r\nUTF-8 文档\n```java\nint x = 1;\n```\n| 列 | 值 |\n| --- | --- |\n| 一 | 二 |\n".getBytes(
            StandardCharsets.UTF_8
        );
    }

    /** 构造包含指定内部部件的 Office 测试容器。 */
    public static Map<String, String> office(DocumentFormat format) {
        String main = format == DocumentFormat.DOCX ? "word/document.xml" : "xl/workbook.xml";
        String type = format == DocumentFormat.DOCX ? "wordprocessingml.document" : "spreadsheetml.sheet";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
            "[Content_Types].xml",
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Override PartName=\"/" +
                main +
                "\" ContentType=\"application/vnd.openxmlformats-officedocument." +
                type +
                ".main+xml\"/></Types>"
        );
        entries.put(
            "_rels/.rels",
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"" +
                main +
                "\"/></Relationships>"
        );
        if (format == DocumentFormat.DOCX) {
            entries.put(
                main,
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>中文 DOCX</w:t></w:r></w:p></w:body></w:document>"
            );
        } else {
            entries.put(
                main,
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"测试\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
            );
            entries.put(
                "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
            );
            entries.put(
                "xl/worksheets/sheet1.xml",
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>中文 XLSX</t></is></c></row></sheetData></worksheet>"
            );
        }
        return entries;
    }

    /** 将指定内部部件打包为测试压缩容器。 */
    public static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 构造测试使用的文档或解析资源限制配置。 */
    public static DocumentProperties properties(int maxBytes) {
        return new DocumentProperties(
            maxBytes,
            2000000,
            2000,
            104857600,
            20971520,
            200,
            2000,
            60,
            java.time.Duration.ofSeconds(30),
            java.time.Duration.ofHours(1),
            java.time.Duration.ofMinutes(5),
            10
        );
    }

    /** 生成或检查文档测试样本，供人工验证夹具使用。 */
    public static void main(String[] args) throws Exception {
        java.nio.file.Path directory = java.nio.file.Path.of(args[0]);
        java.nio.file.Files.createDirectories(directory);
        for (DocumentFormat format : DocumentFormat.values()) {
            java.nio.file.Files.write(directory.resolve("sample." + format.extension()), content(format));
        }
    }
}
