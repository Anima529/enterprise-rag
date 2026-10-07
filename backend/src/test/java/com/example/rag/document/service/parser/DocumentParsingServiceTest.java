package com.example.rag.document.service.parser;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.config.parser.ParserProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.support.DocumentFixtures;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class DocumentParsingServiceTest {

    /** 构造测试使用的文档或解析资源限制配置。 */
    static ParserProperties properties() {
        return new ParserProperties(Duration.ofSeconds(30), 10000, 50, 50000, 256, 500000, 2, 100000, 3, 0.6, 12, 4, 4);
    }

    /** 组合测试解析器和预算配置以创建解析服务。 */
    static DocumentParsingService service(ParserProperties properties, DocumentProperties documents) {
        return new DocumentParsingService(
            List.of(
                new PdfDocumentParser(),
                new DocDocumentParser(),
                new DocxDocumentParser(),
                new ExcelDocumentParser(),
                new TxtDocumentParser(),
                new MarkdownDocumentParser()
            ),
            properties,
            documents
        );
    }

    private final DocumentParsingService parser = service(properties(), DocumentFixtures.properties(10485760));

    /** 使用用例指定格式和预算执行解析。 */
    private ParsedDocument parse(DocumentFormat format, byte[] bytes) {
        return parser.parse(format, new ByteArrayInputStream(bytes));
    }

    /** 构造测试正文或文本块，保留用例所需来源位置。 */
    private ParsedDocument text(DocumentFormat format, String text) {
        return parse(format, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 验证全部真实上传格式可提取并保留来源位置。 */
    @Test
    void shouldExtractAllSupportedActualUploadFixturesWithSourceLocations() throws Exception {
        for (DocumentFormat format : DocumentFormat.values()) {
            ParsedDocument result = parse(format, DocumentFixtures.content(format));
            assertThat(result.format()).isEqualTo(format);
            assertThat(result.blocks()).isNotEmpty();
            assertThat(result.blocks().getFirst().text()).contains(format == DocumentFormat.PDF ? "Phase 4" : "中文");
            if (format == DocumentFormat.PDF) {
                assertThat(result.blocks().getFirst().page()).isEqualTo(1);
            }
            if (format == DocumentFormat.DOC || format == DocumentFormat.DOCX) {
                assertThat(result.blocks().getFirst().metadata()).containsEntry("paragraphIndex", 1);
            }
            if (format == DocumentFormat.XLSX) {
                assertThat(result.blocks().getFirst().metadata()).containsEntry("sheetName", "测试");
            }
            if (format == DocumentFormat.TXT || format == DocumentFormat.MD) {
                assertThat(result.blocks().getFirst().metadata()).containsEntry("lineStart", 1);
            }
        }
    }

    /** 验证 UTF-8 BOM、段落和原始行范围。 */
    @Test
    void shouldKeepUtf8BomParagraphsAndOriginalLineRanges() {
        ParsedDocument result = text(DocumentFormat.TXT, "\uFEFF第一行\r\n第二行\r\n\r\n第四行\r第五行\n");
        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks().getFirst().text()).isEqualTo("第一行\n第二行");
        assertThat(result.blocks().getFirst().metadata()).containsEntry("lineStart", 1).containsEntry("lineEnd", 2);
        assertThat(result.blocks().getLast().metadata()).containsEntry("lineStart", 4).containsEntry("lineEnd", 5);
    }

    /** 验证 Markdown 标题、列表、表格和代码，代码内标题不改变章节。 */
    @Test
    void shouldPreserveMarkdownHeadingPathsListsTablesAndCodeWithoutInterpretingCodeHeadings() {
        ParsedDocument result = text(
            DocumentFormat.MD,
            "总则\n====\n\n## 报销\n\n- 条款甲\n- 条款乙\n\n| 项目 | 金额 |\n| --- | --- |\n| 差旅 | 100 |\n\n```java\n# 此行不是标题\nint 金额 = 100;\n```\n\n结束\n"
        );
        assertThat(result.blocks()).hasSize(7);
        assertThat(result.blocks().get(2).metadata()).containsEntry("kind", "LIST");
        assertThat(result.blocks().get(3).metadata()).containsEntry("kind", "TABLE_ROW");
        assertThat(result.blocks().get(4).text()).contains("项目 [A]=差旅", "金额 [B]=100");
        ParsedBlock code = result.blocks().get(5);
        assertThat(code.text()).isEqualTo("```java\n# 此行不是标题\nint 金额 = 100;\n```");
        assertThat(code.section()).isEqualTo("总则 / 报销");
        assertThat(code.metadata())
            .containsEntry("kind", "CODE")
            .containsEntry("language", "java")
            .containsEntry("lineStart", 13)
            .containsEntry("lineEnd", 16)
            .containsEntry("closed", true);
        assertThat(result.blocks().getLast().section()).isEqualTo("总则 / 报销");
    }

    /** 验证未闭合代码围栏保留且新章节重置标题路径。 */
    @Test
    void shouldRetainUnclosedFencesAndResetHeadingPathAtNewChapter() {
        ParsedDocument result = text(DocumentFormat.MD, "# 第一章\n## 子节\n# 第二章\n~~~text\n保留尾部\n");
        assertThat(result.blocks().getLast().section()).isEqualTo("第二章");
        assertThat(result.blocks().getLast().metadata()).containsEntry("closed", false);
        assertThat(result.blocks().getLast().text()).contains("保留尾部");
    }

    /** 验证围栏优先于 Setext，保留字面量尾部井号。 */
    @Test
    void shouldKeepFencePrecedenceOverSetextAndLiteralTrailingHeadingHashes() {
        ParsedDocument result = text(DocumentFormat.MD, "# C#\n\n```text\n---\n代码\n```\n");
        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks().getLast().metadata()).containsEntry("kind", "CODE").containsEntry("closed", true);
        assertThat(result.blocks().getLast().section()).isEqualTo("C#");
        assertThat(result.blocks().getLast().text()).contains("\n---\n");
    }

    /** 验证 Markdown 与 Office 外链从不被主动请求。 */
    @Test
    void shouldNeverFetchMarkdownLinksImagesHtmlOrOfficeExternalHyperlinks() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/secret";
            String source =
                "# 不可信内容\n\n<script>fetch('" + url + "')</script>\n[链接](" + url + ") ![图片](" + url + ")";
            assertThat(text(DocumentFormat.MD, source).blocks().getLast().text()).contains("<script>", url);
            Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.DOCX));
            entries.put(
                "word/_rels/document.xml.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"link\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" TargetMode=\"External\" Target=\"" +
                    url +
                    "\"/></Relationships>"
            );
            parse(DocumentFormat.DOCX, DocumentFixtures.zip(entries));
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    /** 验证 DOCX 自定义与继承标题样式及表格位置。 */
    @Test
    void shouldResolveDocxCustomAndInheritedHeadingStylesAndPreserveTablePositions() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.DOCX));
        entries.put(
            "word/styles.xml",
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:styleId=\"base\"><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr></w:style><w:style w:styleId=\"custom\"><w:basedOn w:val=\"base\"/></w:style></w:styles>"
        );
        entries.put(
            "word/document.xml",
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:pPr><w:pStyle w:val=\"custom\"/></w:pPr><w:r><w:t>公司制度</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t>差旅</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>报销金额</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"
        );
        ParsedDocument result = parse(DocumentFormat.DOCX, DocumentFixtures.zip(entries));
        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks().getLast().section()).isEqualTo("公司制度 / 差旅");
        assertThat(result.blocks().getLast().metadata())
            .containsEntry("paragraphIndex", 3)
            .containsEntry("tableIndex", 1)
            .containsEntry("rowStart", 1)
            .containsEntry("tableCells", Map.of("A", "报销金额"));
    }

    /** 验证 Excel 稀疏单元格、表头、共享文本和公式缓存对应正确。 */
    @Test
    void shouldAssociateSparseExcelCellsWithCorrectHeadersSharedStringsAndCachedFormula() throws Exception {
        ParsedDocument result = parse(DocumentFormat.XLSX, excel());
        assertThat(result.blocks()).hasSize(4);
        ParsedBlock group = result.blocks().get(1);
        assertThat(group.text()).contains("部门 [A]=研发", "金额 [C]=100");
        assertThat(result.blocks().get(2).text()).contains("部门 [A]=人事", "金额 [C]=200");
        assertThat(group.metadata())
            .containsEntry("rowStart", 2)
            .containsEntry("rowEnd", 2)
            .containsEntry("header", Map.of("A", "部门", "C", "金额"));
        assertThat(result.blocks().getLast().text()).contains("[公式无缓存] =1+2");
        assertThat(result.blocks().getLast().metadata()).containsEntry("rowStart", 5);
    }

    /** 生成含指定工作表结构的 XLSX 测试内容。 */
    static byte[] excel() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put(
            "xl/sharedStrings.xml",
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>部门</t></si><si><r><t>研</t></r><r><t>发</t></r></si></sst>"
        );
        entries.put(
            "xl/worksheets/sheet1.xml",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:XFD1048576\"/><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"inlineStr\"><is><t>金额</t></is></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"C2\"><v>100</v></c></row>" +
                "<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>人事</t></is></c><c r=\"C4\"><f>100*2</f><v>200</v></c></row>" +
                "<row r=\"5\"><c r=\"C5\"><f>1+2</f></c></row></sheetData></worksheet>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 验证仅删除重复 PDF 页边文字且保留原始页码。 */
    @Test
    void shouldRemoveOnlyRepeatedPdfMarginsAndRetainOriginalPageNumbers() throws Exception {
        ParsedDocument result = parse(DocumentFormat.PDF, pdf(3, true));
        assertThat(result.blocks()).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(result.blocks().get(i).page()).isEqualTo(i + 1);
            assertThat(result.blocks().get(i).text()).isEqualTo("Body " + (i + 1));
        }
        assertThat(result.warnings()).contains("REPEATED_MARGIN_LINES_REMOVED_6");
    }

    /** 验证工作表顺序、独立表头和工作表数量限制。 */
    @Test
    void shouldRespectWorkbookSheetOrderPerSheetHeadersAndSheetCountLimit() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put(
            "xl/workbook.xml",
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"第二张\" sheetId=\"2\" r:id=\"rId2\"/><sheet name=\"第一张\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        );
        entries.put(
            "xl/_rels/workbook.xml.rels",
            entries
                .get("xl/_rels/workbook.xml.rels")
                .replace(
                    "</Relationships>",
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/></Relationships>"
                )
        );
        String sheet =
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>HEADER</t></is></c></row><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>VALUE</t></is></c></row></sheetData></worksheet>";
        entries.put("xl/worksheets/sheet1.xml", sheet.replace("HEADER", "部门").replace("VALUE", "研发"));
        entries.put("xl/worksheets/sheet2.xml", sheet.replace("HEADER", "项目").replace("VALUE", "差旅"));
        byte[] content = DocumentFixtures.zip(entries);
        ParsedDocument result = parse(DocumentFormat.XLSX, content);
        assertThat(result.blocks().get(1).text()).contains("工作表：第二张", "项目 [A]=差旅");
        assertThat(result.blocks().get(3).text()).contains("工作表：第一张", "部门 [A]=研发");
        ParserProperties limited = new ParserProperties(
            Duration.ofSeconds(30),
            10000,
            1,
            50000,
            256,
            500000,
            2,
            100000,
            3,
            0.6,
            12,
            4,
            4
        );
        assertThatThrownBy(() ->
            service(limited, DocumentFixtures.properties(10485760)).parse(
                DocumentFormat.XLSX,
                new ByteArrayInputStream(content)
            )
        ).isInstanceOfSatisfying(IndexFailure.class, failure ->
            assertThat(failure.code()).isEqualTo("DOC_413_PARSE_LIMIT")
        );
    }

    /** 生成含指定页内容的 PDF 测试样本。 */
    static byte[] pdf(int pages, boolean text) throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                if (text) {
                    try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(72, 720);
                        stream.showText("Repeated Header");
                        stream.newLineAtOffset(0, -30);
                        stream.showText("Body " + (i + 1));
                        stream.newLineAtOffset(0, -30);
                        stream.showText("Repeated Footer");
                        stream.endText();
                    }
                } else {
                    try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                            64,
                            64,
                            java.awt.image.BufferedImage.TYPE_INT_RGB
                        );
                        stream.drawImage(
                            org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, image),
                            72,
                            600,
                            64,
                            64
                        );
                    }
                }
            }
            pdf.save(output);
            return output.toByteArray();
        }
    }

    /** 验证无文本层 PDF 返回可见 OCR 原因。 */
    @Test
    void shouldRejectPdfWithoutTextLayerWithVisibleOcrReason() throws Exception {
        assertFailure(DocumentFormat.PDF, pdf(1, false), "DOC_422_OCR_REQUIRED");
    }

    /** 验证非法 UTF-8、二进制、空或损坏文档失败且不泄露正文。 */
    @Test
    void shouldRejectMalformedUtf8BinaryEmptyAndCorruptDocumentsWithoutLeakingContent() {
        assertFailure(DocumentFormat.TXT, new byte[] { (byte) 0xff }, "DOC_422_PARSE_FAILED");
        assertFailure(DocumentFormat.TXT, new byte[] { 0, 1 }, "DOC_422_PARSE_FAILED");
        assertFailure(DocumentFormat.MD, " \n".getBytes(StandardCharsets.UTF_8), "DOC_422_EMPTY_TEXT");
        for (DocumentFormat format : List.of(
            DocumentFormat.PDF,
            DocumentFormat.DOC,
            DocumentFormat.DOCX,
            DocumentFormat.XLSX
        )) {
            assertFailure(format, "secret-corrupt-data".getBytes(StandardCharsets.UTF_8), "DOC_422_PARSE_FAILED");
        }
    }

    /** 验证 XML 实体和外部工作表目标被拒绝。 */
    @Test
    void shouldRejectXmlEntitiesAndExternalWorksheetTargets() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.DOCX));
        entries.put(
            "word/document.xml",
            "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///secret'>]><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>&a;</w:t></w:r></w:p></w:body></w:document>"
        );
        assertFailure(DocumentFormat.DOCX, DocumentFixtures.zip(entries), "DOC_422_PARSE_FAILED");
        entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put(
            "xl/_rels/workbook.xml.rels",
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" TargetMode=\"External\" Target=\"http://127.0.0.1/secret\"/></Relationships>"
        );
        assertFailure(DocumentFormat.XLSX, DocumentFixtures.zip(entries), "DOC_422_PARSE_FAILED");
    }

    /** 验证行、列、单元格、工作表、块及输出字符资源上限。 */
    @Test
    void shouldBoundRowsColumnsCellsSheetsBlocksAndOutputCharacters() throws Exception {
        for (ParserProperties limited : List.of(
            new ParserProperties(Duration.ofSeconds(30), 10000, 50, 3, 256, 500000, 2, 100000, 3, 0.6, 12, 4, 4),
            new ParserProperties(Duration.ofSeconds(30), 10000, 50, 50000, 2, 500000, 2, 100000, 3, 0.6, 12, 4, 4),
            new ParserProperties(Duration.ofSeconds(30), 10000, 50, 50000, 256, 2, 2, 100000, 3, 0.6, 12, 4, 4),
            new ParserProperties(Duration.ofSeconds(30), 1, 50, 50000, 256, 500000, 2, 100000, 3, 0.6, 12, 4, 4)
        )) {
            DocumentParsingService bounded = service(limited, DocumentFixtures.properties(10485760));
            assertThatThrownBy(() ->
                bounded.parse(DocumentFormat.XLSX, new ByteArrayInputStream(excel()))
            ).isInstanceOfSatisfying(IndexFailure.class, failure ->
                assertThat(failure.code()).isEqualTo("DOC_413_PARSE_LIMIT")
            );
        }
        DocumentParsingService bounded = service(
            properties(),
            new DocumentProperties(
                100,
                3,
                2000,
                104857600,
                20971520,
                200,
                2000,
                60,
                Duration.ofSeconds(30),
                Duration.ofHours(1),
                Duration.ofMinutes(5),
                10
            )
        );
        assertThatThrownBy(() ->
            bounded.parse(DocumentFormat.TXT, new ByteArrayInputStream("四个字符".getBytes(StandardCharsets.UTF_8)))
        ).isInstanceOfSatisfying(IndexFailure.class, failure ->
            assertThat(failure.code()).isEqualTo("DOC_413_PARSE_LIMIT")
        );
    }

    /** 验证截止时间和线程中断及时终止解析。 */
    @Test
    void shouldStopOnDeadlineAndThreadInterruption() {
        ParserProperties tiny = new ParserProperties(
            Duration.ofNanos(1),
            10000,
            50,
            50000,
            256,
            500000,
            2,
            100000,
            3,
            0.6,
            12,
            4,
            4
        );
        assertThatThrownBy(() ->
            service(tiny, DocumentFixtures.properties(100)).parse(
                DocumentFormat.TXT,
                new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8))
            )
        ).isInstanceOfSatisfying(IndexFailure.class, failure ->
            assertThat(failure.code()).isEqualTo("DOC_504_PARSE_TIMEOUT")
        );
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> text(DocumentFormat.TXT, "正文")).isInstanceOf(LostTaskLease.class);
        } finally {
            Thread.interrupted();
        }
    }

    /** 验证重复标题元数据在持久化前受累计字符限制。 */
    @Test
    void shouldBoundRepeatedHeadingMetadataBeforePersistingAmplifiedOutput() {
        DocumentProperties documents = new DocumentProperties(
            10000,
            300,
            2000,
            104857600,
            20971520,
            200,
            2000,
            60,
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofMinutes(5),
            10
        );
        String source = "# " + "标题".repeat(30) + "\n\n第一段\n\n第二段\n\n第三段";
        assertThat(source.length()).isLessThan(300);
        assertThatThrownBy(() ->
            service(properties(), documents).parse(
                DocumentFormat.MD,
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))
            )
        ).isInstanceOfSatisfying(IndexFailure.class, failure ->
            assertThat(failure.code()).isEqualTo("DOC_413_PARSE_LIMIT")
        );
    }

    /** 断言解析失败使用预期阶段错误码。 */
    private void assertFailure(DocumentFormat format, byte[] bytes, String code) {
        assertThatThrownBy(() -> parse(format, bytes)).isInstanceOfSatisfying(IndexFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).doesNotContain("secret");
            assertThat(failure.retryable()).isFalse();
        });
    }
}
