package com.example.rag.document.service.parser;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.dto.preview.SourcePreviewModels.*;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.document.service.chunk.StructureAwareParentChildChunkStrategy;
import com.example.rag.document.service.preview.SourcePreviewBuilder;
import com.example.rag.document.support.DocumentFixtures;
import com.example.rag.document.support.LegacyDocFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SourceStructureTest {

    private final DocumentParsingService parser = DocumentParsingServiceTest.service(
        DocumentParsingServiceTest.properties(),
        DocumentFixtures.properties(10485760)
    );
    private final ObjectMapper json = new ObjectMapper();
    private final SourcePreviewBuilder previews = new SourcePreviewBuilder();

    /** 生成含指定工作表结构的 XLSX 测试内容。 */
    private byte[] excel(String rows, String merges) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put(
            "xl/worksheets/sheet1.xml",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                rows +
                "</sheetData>" +
                merges +
                "</worksheet>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 构造指定内容及坐标的测试单元格。 */
    private String cell(String ref, String value) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }

    /** 使用用例指定格式和预算执行解析。 */
    private ParsedDocument parse(DocumentFormat format, byte[] bytes) {
        return parser.parse(format, new ByteArrayInputStream(bytes));
    }

    /** 验证独立标量和合并句子作为正文，不依赖业务关键词识别。 */
    @ParameterizedTest
    @ValueSource(
        strings = { "请按指定基准核对截止日期。", "说明信息跨行\n第二行仍为正文", "未经批准不得使用共享账号。" }
    )
    void shouldTreatIsolatedScalarAndMergedSentencesAsProseWithoutBusinessKeywordRules(String sentence)
        throws Exception {
        for (boolean merged : List.of(false, true)) {
            ParsedDocument document = parse(
                DocumentFormat.XLSX,
                excel(
                    "<row r=\"22\">" + cell("A22", sentence) + "<c r=\"K22\"/></row>",
                    merged ? "<mergeCells><mergeCell ref=\"A22:K22\"/></mergeCells>" : ""
                )
            );
            assertThat(document.blocks()).hasSize(1);
            ParsedBlock block = document.blocks().getFirst();
            assertThat(block.text()).isEqualTo(sentence);
            assertThat(block.metadata())
                .containsEntry("kind", "PARAGRAPH")
                .containsEntry("rowStart", 22)
                .doesNotContainKeys("header", "tableCells", "tableHeader");
            assertThat(block.metadata().get("sourceCellRefs").toString()).contains("A22");
        }
    }

    /** 验证独立行保留网格且不虚构列名。 */
    @ParameterizedTest
    @ValueSource(strings = { "普通项目", "没有汇总关键词", "任意条目" })
    void shouldPreserveAnIndependentRowAsAGridWithoutInventingColumnLabels(String label) throws Exception {
        ParsedDocument document = parse(
            DocumentFormat.XLSX,
            excel("<row r=\"4\">" + cell("A4", label) + cell("C4", "280") + "<c r=\"D4\"/></row>", "")
        );
        ParsedBlock block = document.blocks().getFirst();
        assertThat(block.metadata())
            .containsEntry("tableHeader", false)
            .containsEntry("headerPolicy", "PHYSICAL_COLUMNS")
            .containsEntry("header", Map.of("A", "列 A", "C", "列 C", "D", "列 D"));
        assertThat(block.metadata().get("tableCells")).isEqualTo(Map.of("A", label, "C", "280", "D", ""));
    }

    /** 验证相同值位于不同物理单元格时仍分别保留。 */
    @Test
    void shouldKeepRepeatedEqualValuesInSeparatePhysicalCells() throws Exception {
        ParsedDocument document = parse(
            DocumentFormat.XLSX,
            excel("<row r=\"4\">" + cell("A4", "5") + cell("B4", "5") + "</row>", "")
        );
        assertThat(document.blocks().getFirst().metadata())
            .containsEntry("kind", "TABLE_ROW")
            .containsEntry("tableCells", Map.of("A", "5", "B", "5"));
    }

    /** 验证全部格式的已识别表格使用统一预览构造器。 */
    @ParameterizedTest
    @EnumSource(DocumentFormat.class)
    void shouldPresentRecognizedTablesThroughTheSameBuilderForEverySupportedFormat(DocumentFormat format)
        throws Exception {
        byte[] bytes = switch (format) {
            case MD -> "说明\n\n| Field | Value |\n|---|---|\n| sample | 90 |\n\n结束".getBytes(StandardCharsets.UTF_8);
            case TXT -> "说明\nField\tValue\nsample\t90\nother\t30\n结束".getBytes(StandardCharsets.UTF_8);
            case PDF -> TableParsingTest.pdfTable();
            case DOC -> LegacyDocFixtures.read("simple-table.doc");
            case DOCX -> docx();
            case XLSX -> excel(
                "<row r=\"1\">" +
                    cell("A1", "Field") +
                    cell("B1", "Value") +
                    "</row><row r=\"2\">" +
                    cell("A2", "sample") +
                    cell("B2", "90") +
                    "</row><row r=\"3\"/><row r=\"4\">" +
                    cell("A4", "结束") +
                    "</row>",
                ""
            );
        };
        ParsedDocument document = parse(format, bytes);
        List<ChunkDraft> chunks = new StructureAwareParentChildChunkStrategy(new ChunkTokenizer()).split(
            document,
            new ChunkProperties(350, 1400, 50, 30000, Duration.ofSeconds(30), 250, 100)
        );
        int grids = 0;
        for (ChunkDraft chunk : chunks) {
            if (chunk.chunkType() == ChunkType.PARENT) {
                List<PreviewBlock> preview = previews.preview(json.valueToTree(chunk.sources()), chunk.content());
                assertThat(preview).isNotEmpty();
                grids += (int) preview
                    .stream()
                    .filter(block -> block.type() == PreviewType.TABLE)
                    .count();
                List<PreviewBlock> bounded = previews.preview(
                    json.valueToTree(chunk.sources()),
                    chunk.content().substring(0, Math.min(600, chunk.content().length()))
                );
                assertThat(bounded).isNotEmpty();
            }
        }
        assertThat(grids).as(format.name()).isPositive();
    }

    /** 生成包含指定段落或表格结构的 DOCX 测试样本。 */
    private byte[] docx() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.DOCX));
        entries.put(
            "word/document.xml",
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>说明</w:t></w:r></w:p><w:tbl><w:tr>" +
                wordCell("Field") +
                wordCell("Value") +
                "</w:tr><w:tr>" +
                wordCell("sample") +
                wordCell("90") +
                "</w:tr></w:tbl><w:p><w:r><w:t>结束</w:t></w:r></w:p></w:body></w:document>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 构造 Word 表格单元格 XML。 */
    private String wordCell(String text) {
        return "<w:tc><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:tc>";
    }
}
