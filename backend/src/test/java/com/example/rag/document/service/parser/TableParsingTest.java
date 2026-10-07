package com.example.rag.document.service.parser;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.document.service.chunk.StructureAwareParentChildChunkStrategy;
import com.example.rag.document.support.DocumentFixtures;
import com.example.rag.document.support.LegacyDocFixtures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class TableParsingTest {

    private final DocumentParsingService service = DocumentParsingServiceTest.service(
        DocumentParsingServiceTest.properties(),
        DocumentFixtures.properties(10485760)
    );

    /** 使用用例指定格式和预算执行解析。 */
    private ParsedDocument parse(DocumentFormat format, byte[] bytes) {
        return service.parse(format, new ByteArrayInputStream(bytes));
    }

    /** 构造测试正文或文本块，保留用例所需来源位置。 */
    private ParsedDocument text(DocumentFormat format, String source) {
        return parse(format, source.getBytes(StandardCharsets.UTF_8));
    }

    /** 提取解析产物中的表格行，供结构和顺序断言。 */
    private List<ParsedBlock> rows(ParsedDocument document) {
        return document
            .blocks()
            .stream()
            .filter(block -> "TABLE_ROW".equals(block.metadata().get("kind")))
            .toList();
    }

    /** 生成包含指定段落或表格结构的 DOCX 测试样本。 */
    private byte[] docx(String body) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.DOCX));
        entries.put(
            "word/document.xml",
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                body +
                "</w:body></w:document>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 构造指定内容及坐标的测试单元格。 */
    private String cell(String properties, String value) {
        return "<w:tc><w:tcPr>" + properties + "</w:tcPr><w:p><w:r><w:t>" + value + "</w:t></w:r></w:p></w:tc>";
    }

    /** 验证 DOC 表格列和空单元格不被扁平化为段落。 */
    @Test
    void shouldPreserveDocColumnsAndEmptyCellsInsteadOfFlatteningThemIntoParagraphs() throws Exception {
        ParsedDocument document = parse(DocumentFormat.DOC, LegacyDocFixtures.read("simple-table.doc"));
        assertThat(rows(document)).hasSizeGreaterThan(1);
        assertThat(rows(document)).allSatisfy(row -> {
            assertThat(row.metadata()).containsKeys("header", "tableCells", "paragraphIndex", "rowStart", "tableIndex");
            assertThat(row.text()).contains(" [A]=");
        });
    }

    /** 验证 DOCX 空单元格、多行单元格及后续段落的顺序。 */
    @Test
    void shouldKeepDocxBlankCellsMultilineCellsAndFollowingParagraphsInOrder() throws Exception {
        String body =
            "<w:tbl><w:tblGrid><w:gridCol/><w:gridCol/><w:gridCol/></w:tblGrid><w:tr>" +
            cell("", "Field") +
            cell("", "Key") +
            cell("", "Type") +
            "</w:tr><w:tr>" +
            cell("", "ip_address") +
            cell("", "") +
            "<w:tc><w:p><w:r><w:t>varchar</w:t></w:r></w:p><w:p><w:r><w:t>64</w:t></w:r></w:p></w:tc>" +
            "</w:tr></w:tbl><w:p><w:r><w:t>After table</w:t></w:r></w:p>";
        ParsedDocument document = parse(DocumentFormat.DOCX, docx(body));
        assertThat(document.blocks()).hasSize(3);
        assertThat(rows(document).get(1).metadata().get("tableCells")).isEqualTo(
            Map.of("A", "ip_address", "B", "", "C", "varchar\n64")
        );
        assertThat(rows(document).get(1).text()).contains("Key [B]=（空）", "Type [C]=varchar / 64");
        assertThat(document.blocks().getLast().text()).isEqualTo("After table");
    }

    /** 验证 DOCX 显式表头组合且仅继承显式纵向合并。 */
    @Test
    void shouldCombineExplicitDocxHeadersAndInheritOnlyExplicitVerticalMerges() throws Exception {
        String header = "<w:trPr><w:tblHeader/></w:trPr>";
        String body =
            "<w:tbl><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid><w:tr>" +
            header +
            cell("<w:gridSpan w:val=\"2\"/>", "Schema") +
            "</w:tr><w:tr>" +
            header +
            cell("", "Field") +
            cell("", "Type") +
            "</w:tr><w:tr>" +
            cell("<w:vMerge w:val=\"restart\"/>", "ip_address") +
            cell("", "varchar") +
            "</w:tr><w:tr>" +
            cell("<w:vMerge/>", "") +
            cell("", "text") +
            "</w:tr><w:tr>" +
            cell("", "") +
            cell("", "int") +
            "</w:tr></w:tbl>";
        List<ParsedBlock> rows = rows(parse(DocumentFormat.DOCX, docx(body)));
        assertThat(rows).hasSize(5);
        assertThat(rows.get(2).metadata().get("header")).isEqualTo(Map.of("A", "Schema / Field", "B", "Schema / Type"));
        assertThat(rows.get(3).metadata().get("tableCells")).isEqualTo(Map.of("A", "ip_address", "B", "text"));
        assertThat(rows.get(4).metadata().get("tableCells")).isEqualTo(Map.of("A", "", "B", "int"));
    }

    /** 验证 Markdown 转义竖线、空单元格和字面 HTML 不吞掉后续正文。 */
    @Test
    void shouldParseMarkdownEscapedPipesEmptyCellsAndLiteralHtmlWithoutConsumingFollowingProse() {
        ParsedDocument document = text(
            DocumentFormat.MD,
            "| Field | Key | Type |\n| --- | --- | --- |\n| ip\\|address | | <script>untrusted</script> |\nFollowing prose\n"
        );
        List<ParsedBlock> rows = rows(document);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).metadata().get("tableCells")).isEqualTo(
            Map.of("A", "ip|address", "B", "", "C", "<script>untrusted</script>")
        );
        assertThat(rows.get(1).metadata()).containsEntry("lineStart", 3).containsEntry("lineEnd", 3);
        assertThat(document.blocks().getLast().text()).isEqualTo("Following prose");
        assertThat(
            text(DocumentFormat.MD, "```text\n| a | b |\n| --- | --- |\n| 1 | 2 |\n```").blocks().getFirst().metadata()
        ).containsEntry("kind", "CODE");
    }

    /** 验证 TXT 制表及框线表格保留空列位置。 */
    @Test
    void shouldKeepTxtTabAndBoxedTablesAsRowsWithoutShiftingEmptyColumns() {
        ParsedDocument document = text(
            DocumentFormat.TXT,
            "Before\nField\tKey\tType\nip_address\t\tvarchar\nAfter\n\n| Name | Amount |\n| --- | --- |\n| alpha | 100 |\n"
        );
        assertThat(rows(document)).hasSize(4);
        assertThat(rows(document).get(1).metadata().get("tableCells")).isEqualTo(
            Map.of("A", "ip_address", "B", "", "C", "varchar")
        );
        assertThat(document.blocks().getFirst().text()).isEqualTo("Before");
        assertThat(document.blocks().get(3).text()).isEqualTo("After");
    }

    /** 验证对齐文本和带引号 CSV 被识别，普通正文不被误判。 */
    @Test
    void shouldRecognizeAlignedTxtAndQuotedCsvButNotOrdinaryProse() {
        assertThat(
            rows(text(DocumentFormat.TXT, "Name     Type     Size\nip       varchar  64\ncode     int      4"))
        ).hasSize(3);
        List<ParsedBlock> csv = rows(text(DocumentFormat.TXT, "Name,Note,Size\nip,\"a,b\",64\ncode,empty,4"));
        assertThat(csv).hasSize(3);
        assertThat(csv.get(1).metadata().get("tableCells")).isEqualTo(Map.of("A", "ip", "B", "a,b", "C", "64"));
        assertThat(
            rows(
                text(
                    DocumentFormat.TXT,
                    "This is ordinary prose.\nAnother line, with punctuation.\nNothing is tabular."
                )
            )
        ).isEmpty();
    }

    /** 验证 Excel 合并标题、合并数据、稀疏行和多表结构。 */
    @Test
    void shouldHandleExcelMergedTitlesMergedDataSparseRowsAndMultipleTables() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put(
            "xl/worksheets/sheet1.xml",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                excelRow(1, "Schema", null) +
                excelRow(2, "Field", "Type") +
                excelRow(3, "ip_address", "varchar") +
                excelRow(4, null, "text") +
                "<row r=\"5\"/>" +
                excelRow(7, "Name", "Amount") +
                excelRow(9, "alpha", "100") +
                "</sheetData><mergeCells><mergeCell ref=\"A1:B1\"/><mergeCell ref=\"A3:A4\"/></mergeCells></worksheet>"
        );
        List<ParsedBlock> rows = rows(parse(DocumentFormat.XLSX, DocumentFixtures.zip(entries)));
        assertThat(rows).hasSize(6);
        assertThat(rows.get(2).metadata().get("header")).isEqualTo(Map.of("A", "Schema / Field", "B", "Schema / Type"));
        assertThat(rows.get(3).metadata().get("tableCells")).isEqualTo(Map.of("A", "ip_address", "B", "text"));
        assertThat(rows.get(5).metadata()).containsEntry("rowStart", 9).containsEntry("tableIndex", 2);
        assertThat(rows.get(5).text()).contains("Name [A]=alpha", "Amount [B]=100");
    }

    /** 构造保留物理行号的工作表行 XML。 */
    private String excelRow(int row, String left, String right) {
        return "<row r=\"" + row + "\">" + excelCell("A", row, left) + excelCell("B", row, right) + "</row>";
    }

    /** 构造保留真实坐标的工作表单元格 XML。 */
    private String excelCell(String column, int row, String value) {
        return value == null ? "" : "<c r=\"" + column + row + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }

    /** 验证仅对齐 PDF 内容被识别为表格且周围正文与页码保留。 */
    @Test
    void shouldExtractOnlyAlignedPdfTablesAndRetainSurroundingTextAndPages() throws Exception {
        ParsedDocument document = parse(DocumentFormat.PDF, pdfTable());
        assertThat(rows(document)).hasSize(3);
        assertThat(rows(document).get(1).metadata().get("tableCells")).isEqualTo(
            Map.of("A", "ip_address", "B", "varchar", "C", "64")
        );
        assertThat(rows(document).get(2).metadata().get("tableCells")).isEqualTo(
            Map.of("A", "code", "B", "", "C", "4")
        );
        assertThat(rows(document)).allSatisfy(row -> assertThat(row.page()).isEqualTo(1));
        assertThat(document.blocks().getFirst().text()).contains("Before table");
        assertThat(document.blocks().getLast().text()).contains("After table");
        assertThat(document.warnings()).contains("PDF_TABLES_INFERRED_FROM_ALIGNED_TEXT");
    }

    /** 生成具有对齐列和周围正文的 PDF 表格样本。 */
    static byte[] pdfTable() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                draw(stream, 50, 750, "Before table");
                String[][] rows = {
                    { "Field", "Type", "Size" },
                    { "ip_address", "varchar", "64" },
                    { "code", "", "4" },
                };
                for (int row = 0; row < rows.length; row++) {
                    for (int column = 0; column < rows[row].length; column++) {
                        draw(stream, 50 + column * 160, 700 - row * 25, rows[row][column]);
                    }
                }
                draw(stream, 50, 580, "After table");
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    /** 在 PDF 指定坐标写入测试文字。 */
    private static void draw(PDPageContentStream stream, float x, float y, String value) throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    /** 验证完整表格行切分，子块含表头、精确来源且满足 Token 上限。 */
    @Test
    void shouldPackWholeRowsAndKeepHeadersInEveryChildWithExactSourcesAndTokenCaps() {
        StringBuilder source = new StringBuilder("Field\tType\tSize\n");
        for (int row = 0; row < 40; row++) {
            source.append("field_").append(row).append("\tvarchar\t64\n");
        }
        ParsedDocument document = text(DocumentFormat.TXT, source.toString());
        ChunkProperties config = new ChunkProperties(64, 256, 10, 30000, Duration.ofSeconds(30), 250, 100);
        ChunkTokenizer tokenizer = new ChunkTokenizer();
        List<ChunkDraft> chunks = new StructureAwareParentChildChunkStrategy(tokenizer).split(document, config);
        Set<Integer> covered = new HashSet<>();
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(chunk.chunkType() == ChunkType.CHILD ? 64 : 256);
            for (ChunkDraft.SourceSpan span : chunk.sources()) {
                assertThat(span.charStart()).isZero();
                assertThat(span.charEnd()).isEqualTo(document.blocks().get(span.blockIndex()).text().length());
                assertThat(span.metadata()).containsEntry("tableRowComplete", true).containsKey("header");
                if (chunk.chunkType() == ChunkType.CHILD) {
                    covered.add(span.blockIndex());
                }
            }
            assertThat(chunk.content()).contains("Type [B]", "Size [C]");
        }
        assertThat(covered).hasSize(document.blocks().size());
    }

    /** 验证超长表格行片段保留所有单元格及来源覆盖。 */
    @Test
    void shouldMarkOverlongTableRowFragmentsWithoutLosingCellsOrSourceCoverage() {
        ParsedDocument document = text(
            DocumentFormat.TXT,
            "Field\tDescription\nip_address\t" + "a long description; ".repeat(120)
        );
        ChunkProperties config = new ChunkProperties(64, 256, 10, 30000, Duration.ofSeconds(30), 250, 100);
        List<ChunkDraft> chunks = new StructureAwareParentChildChunkStrategy(new ChunkTokenizer()).split(
            document,
            config
        );
        BitSet covered = new BitSet();
        boolean partial = false;
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(chunk.chunkType() == ChunkType.CHILD ? 64 : 256);
            for (ChunkDraft.SourceSpan span : chunk.sources()) {
                assertThat(span.metadata()).containsKey("tableCells").containsKey("header");
                if (span.blockIndex() != 1 || chunk.chunkType() != ChunkType.CHILD) {
                    continue;
                }
                assertThat(span.metadata()).containsEntry("tableRowComplete", false);
                covered.set(span.charStart(), span.charEnd());
                partial = true;
            }
        }
        assertThat(partial).isTrue();
        assertThat(covered.nextClearBit(0)).isEqualTo(document.blocks().get(1).text().length());
    }
}
