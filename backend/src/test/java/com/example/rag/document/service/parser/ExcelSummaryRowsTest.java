package com.example.rag.document.service.parser;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.conversation.dto.ConversationModels.*;
import com.example.rag.conversation.service.CitationPreviewBuilder;
import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.dto.preview.SourcePreviewModels.*;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.document.service.chunk.StructureAwareParentChildChunkStrategy;
import com.example.rag.document.support.DocumentFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExcelSummaryRowsTest {

    private final DocumentParsingService parser = DocumentParsingServiceTest.service(
        DocumentParsingServiceTest.properties(),
        DocumentFixtures.properties(10485760)
    );

    /** 使用用例指定格式和预算执行解析。 */
    private ParsedDocument parse(boolean blank, String label, boolean numeric) throws Exception {
        return parser.parse(
            DocumentFormat.XLSX,
            new ByteArrayInputStream(ExcelSummaryFixtures.budget(blank, label, numeric))
        );
    }

    /** 构造保留列定义与来源位置的表格测试行。 */
    private ParsedBlock row(ParsedDocument document, int row) {
        return document
            .blocks()
            .stream()
            .filter(block -> Integer.valueOf(row).equals(block.metadata().get("rowStart")))
            .findFirst()
            .orElseThrow();
    }

    /** 验证分隔后的汇总沿用前表头并保留公式缓存值。 */
    @ParameterizedTest
    @ValueSource(strings = { "合计", "总计", "小计", "汇总（含税）", "Total", "Grand Total" })
    void shouldKeepSeparatedSummaryAsDataWithPreviousHeadersAndCachedFormulaValues(String label) throws Exception {
        ParsedDocument document = parse(true, label, true);
        ParsedBlock total = row(document, 14);
        assertThat(total.metadata()).containsEntry("tableHeader", false).containsEntry("headerRow", 6);
        assertThat(total.metadata().get("header")).isEqualTo(row(document, 7).metadata().get("header"));
        assertThat(total.text())
            .contains("季度预算 [B]=560000", "已验收应付 [C]=531000", "预算余量 [D]=29000")
            .doesNotContain("560000 [B]", "531000 [C]");
        assertThat(total.metadata().get("tableCells")).isEqualTo(
            Map.of("A", label, "B", "560000", "C", "531000", "D", "29000", "E", "0.9482142857142857", "F", "", "G", "")
        );
    }

    /** 验证独立表格使用各自最新表头处理汇总。 */
    @Test
    void shouldKeepIndependentTablesAndUseLatestHeadersForTheirTotals() throws Exception {
        ParsedDocument document = parse(true, "合计", true);
        ParsedBlock first = row(document, 14);
        ParsedBlock second = row(document, 20);
        assertThat(second.metadata())
            .containsEntry("tableHeader", false)
            .containsEntry("headerRow", 17)
            .containsEntry("header", Map.of("A", "项目", "B", "成本"));
        assertThat(second.text()).contains("成本 [B]=1234").doesNotContain("季度预算");
        assertThat(second.metadata().get("tableIndex")).isNotEqualTo(first.metadata().get("tableIndex"));
        ParsedBlock numericColumnNames = row(parse(true, "合计", false), 14);
        assertThat(numericColumnNames.metadata())
            .containsEntry("tableHeader", false)
            .containsEntry("headerPolicy", "PHYSICAL_COLUMNS");
    }

    /** 验证没有显式空行分隔时保留原有解析行为。 */
    @Test
    void shouldRetainExistingBehaviourWhenNoExplicitBlankRowSeparatesTotals() throws Exception {
        ParsedDocument document = parse(false, "合计", true);
        assertThat(row(document, 14).metadata()).containsEntry("tableHeader", false);
        assertThat(row(document, 14).metadata().get("header")).isEqualTo(row(document, 7).metadata().get("header"));
    }

    /** 验证横向指标汇总不套用资产明细表头。 */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldNormalizeHorizontalMetricsWithoutUsingAssetDetailHeaders(boolean blank) throws Exception {
        ParsedDocument document = parser.parse(
            DocumentFormat.XLSX,
            new ByteArrayInputStream(ExcelSummaryFixtures.assets(blank, false))
        );
        List<ParsedBlock> metrics = document
            .blocks()
            .stream()
            .filter(block -> Integer.valueOf(20).equals(block.metadata().get("rowStart")))
            .toList();
        assertThat(metrics)
            .hasSize(2)
            .allSatisfy(block ->
                assertThat(block.metadata())
                    .containsEntry("tableHeader", false)
                    .containsEntry("header", Map.of("A", "指标", "B", "数值"))
            );
        assertThat(metrics.get(0).text()).contains("指标 [A20]=资产数量", "数值 [B20]=12").doesNotContain("资产类别");
        assertThat(metrics.get(1).text())
            .contains("指标 [D20]=原值合计 元", "数值 [G20]=562400")
            .doesNotContain("所属部门");
        assertThat(metrics.get(1).metadata()).containsEntry("sourceCellRefs", Map.of("A", "D20", "B", "G20"));
        assertThat(row(document, 7).metadata().get("header")).isEqualTo(
            Map.of("A", "资产编号", "B", "资产类别", "D", "所属部门", "G", "原值 元")
        );
        assertThat(row(document, 23).metadata())
            .containsEntry("tableHeader", false)
            .containsEntry("headerPolicy", "PHYSICAL_COLUMNS");
    }

    /** 验证合并指标名称及原始单元格范围完整保留。 */
    @Test
    void shouldKeepMergedMetricLabelAndOriginalCellRange() throws Exception {
        ParsedDocument document = parser.parse(
            DocumentFormat.XLSX,
            new ByteArrayInputStream(ExcelSummaryFixtures.assets(true, true))
        );
        List<ParsedBlock> metrics = document
            .blocks()
            .stream()
            .filter(block -> Integer.valueOf(20).equals(block.metadata().get("rowStart")))
            .toList();
        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(1).metadata())
            .containsEntry("labelCellRange", "D20:F20")
            .containsEntry("tableCells", Map.of("A", "原值合计 元", "B", "562400"));
    }

    /** 验证两组横向指标可预览且隐藏片段之外的值。 */
    @Test
    void shouldPreviewBothHorizontalMetricsAndExcludeValuesOutsideTheSnippet() throws Exception {
        ParsedDocument document = parser.parse(
            DocumentFormat.XLSX,
            new ByteArrayInputStream(ExcelSummaryFixtures.assets(true, false))
        );
        ChunkProperties config = new ChunkProperties(350, 1400, 50, 30000, Duration.ofSeconds(30), 250, 100);
        ChunkDraft summary = new StructureAwareParentChildChunkStrategy(new ChunkTokenizer())
            .split(document, config)
            .stream()
            .filter(chunk -> chunk.chunkType() == ChunkType.PARENT && chunk.content().contains("资产数量"))
            .findFirst()
            .orElseThrow();
        ObjectMapper json = new ObjectMapper();
        CitationPreviewBuilder builder = new CitationPreviewBuilder(
            json,
            new com.example.rag.document.service.preview.SourcePreviewBuilder()
        );
        PreviewTable table = builder.tables(json.valueToTree(summary.sources()), 600).getFirst();
        assertThat(table.columns()).extracting(PreviewColumn::label).containsExactly("指标", "数值");
        assertThat(table.rows())
            .extracting(PreviewRow::cells)
            .containsExactly(List.of("资产数量", "12"), List.of("原值合计 元", "562400"));
        int firstLength = summary.sources().getFirst().charEnd() - summary.sources().getFirst().charStart();
        PreviewTable partial = builder.tables(json.valueToTree(summary.sources()), firstLength).getFirst();
        assertThat(partial.rows()).extracting(PreviewRow::cells).containsExactly(List.of("资产数量", "12"));
        assertThat(partial.truncated()).isTrue();
    }

    /** 验证独立汇总 Chunk 可生成表格引用。 */
    @Test
    void shouldBuildATableCitationForStandaloneSummaryChunk() throws Exception {
        ParsedDocument document = parse(true, "合计", true);
        ChunkProperties config = new ChunkProperties(350, 1400, 50, 30000, Duration.ofSeconds(30), 250, 100);
        List<ChunkDraft> chunks = new StructureAwareParentChildChunkStrategy(new ChunkTokenizer()).split(
            document,
            config
        );
        ChunkDraft total = chunks
            .stream()
            .filter(chunk -> chunk.chunkType() == ChunkType.PARENT && chunk.content().contains("560000"))
            .findFirst()
            .orElseThrow();
        ObjectMapper json = new ObjectMapper();
        List<PreviewTable> tables = new CitationPreviewBuilder(
            json,
            new com.example.rag.document.service.preview.SourcePreviewBuilder()
        ).tables(json.valueToTree(total.sources()), 600);
        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst().columns())
            .extracting(PreviewColumn::label)
            .containsExactly("部门", "季度预算", "已验收应付", "预算余量", "执行率", "预算状态", "责任岗位");
        assertThat(tables.getFirst().rows()).anySatisfy(value -> {
            assertThat(value.rowNumber()).isEqualTo(14);
            assertThat(value.cells()).startsWith("合计", "560000", "531000", "29000");
        });
    }
}
