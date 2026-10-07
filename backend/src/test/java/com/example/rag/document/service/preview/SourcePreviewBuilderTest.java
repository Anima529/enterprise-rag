package com.example.rag.document.service.preview;

import static com.example.rag.document.dto.preview.SourcePreviewModels.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourcePreviewBuilderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final SourcePreviewBuilder builder = new SourcePreviewBuilder();

    /** 构造测试正文或文本块，保留用例所需来源位置。 */
    private Map<String, Object> text(String value) {
        return Map.of("charStart", 0, "charEnd", value.length(), "metadata", Map.of("kind", "PARAGRAPH"));
    }

    /** 构造保留列定义与来源位置的表格测试行。 */
    private Map<String, Object> row(
        String value,
        int table,
        boolean header,
        boolean complete,
        String left,
        String right
    ) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("A", "项目");
        columns.put("B", "期限");
        return Map.of(
            "charStart",
            0,
            "charEnd",
            value.length(),
            "metadata",
            Map.of(
                "kind",
                "TABLE_ROW",
                "tableIndex",
                table,
                "tableRowComplete",
                complete,
                "tableHeader",
                header,
                "header",
                columns,
                "tableCells",
                Map.of("A", left, "B", right),
                "rowStart",
                2
            )
        );
    }

    /** 验证段落间表格保留顺序且不依赖业务标签。 */
    @Test
    void shouldRenderTablesBetweenParagraphsWithoutDroppingTextOrDependingOnBusinessLabels() {
        List<Map<String, Object>> sources = List.of(
            text("说明"),
            row("甲30", 1, false, true, "甲", "30"),
            text("中间段"),
            row("乙60", 2, false, true, "乙", "60"),
            text("结束")
        );
        List<PreviewBlock> blocks = builder.preview(
            json.valueToTree(sources),
            "说明\n\n甲30\n\n中间段\n\n乙60\n\n结束"
        );
        assertThat(blocks)
            .extracting(PreviewBlock::type)
            .containsExactly(
                PreviewType.TEXT,
                PreviewType.TABLE,
                PreviewType.TEXT,
                PreviewType.TABLE,
                PreviewType.TEXT
            );
        assertThat(blocks.get(0).text()).isEqualTo("说明");
        assertThat(blocks.get(2).text()).isEqualTo("中间段");
        assertThat(blocks.getLast().text()).isEqualTo("结束");
        assertThat(blocks.get(1).table().rows().getFirst().cells()).containsExactly("甲", "30");
    }

    /** 验证只有表头的网格仍可预览。 */
    @Test
    void shouldKeepHeaderOnlyGridInsteadOfReturningAnEmptyPreview() {
        List<PreviewBlock> blocks = builder.preview(
            json.valueToTree(List.of(row("项目期限", 1, true, true, "项目", "期限"))),
            "项目期限"
        );
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().type()).isEqualTo(PreviewType.TABLE);
        assertThat(blocks.getFirst().table().rows()).isEmpty();
        assertThat(blocks.getFirst().table().columns())
            .extracting(PreviewColumn::label)
            .containsExactly("项目", "期限");
    }

    /** 验证局部片段周围的完整行保留且隐藏未提供单元格。 */
    @Test
    void shouldPreserveCompleteRowsAroundPartialFragmentsWithoutRevealingHiddenCellValues() {
        List<Map<String, Object>> sources = List.of(
            row("甲30", 1, false, true, "甲", "30"),
            row("部分乙", 1, false, false, "乙", "秘密60"),
            row("丙90", 1, false, true, "丙", "90")
        );
        List<PreviewBlock> blocks = builder.preview(json.valueToTree(sources), "甲30\n\n部分乙\n\n丙90");
        assertThat(blocks)
            .extracting(PreviewBlock::type)
            .containsExactly(PreviewType.TABLE, PreviewType.TEXT, PreviewType.TABLE);
        assertThat(blocks.get(1).text()).isEqualTo("部分乙");
        assertThat(blocks.get(1).truncated()).isTrue();
        assertThat(blocks.toString()).doesNotContain("秘密60");
        assertThat(blocks.getLast().table().rows().getFirst().cells()).containsExactly("丙", "90");
    }

    /** 验证引用边界保留可见的局部行。 */
    @Test
    void shouldRespectSnippetBoundaryAndPreserveAVisiblePartialRow() {
        List<Map<String, Object>> sources = List.of(
            row("甲30", 1, false, true, "甲", "30"),
            row("乙秘密60", 1, false, true, "乙", "秘密60")
        );
        List<PreviewBlock> blocks = builder.preview(json.valueToTree(sources), "甲30\n\n乙");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst().table().truncated()).isTrue();
        assertThat(blocks.getLast().text()).isEqualTo("乙");
        assertThat(blocks.toString()).doesNotContain("秘密60");
    }

    /** 验证后续元数据损坏不丢弃已构造表格。 */
    @Test
    void shouldKeepAlreadyRenderedTablesWhenLaterMetadataIsMalformed() {
        List<PreviewBlock> blocks = builder.preview(
            json.valueToTree(List.of(row("甲30", 1, false, true, "甲", "30"), Map.of("metadata", Map.of()))),
            "甲30\n\n原始尾文"
        );
        assertThat(blocks).hasSize(2);
        assertThat(blocks.getLast().text()).isEqualTo("原始尾文");
    }

    /** 验证相同表格 ID 但不同列定义仍分别展示。 */
    @Test
    void shouldSeparateEqualTableIdsWhenTheirColumnDefinitionsDiffer() {
        Map<String, Object> changed = new LinkedHashMap<>(row("乙60", 1, false, true, "乙", "60"));
        Map<String, Object> metadata = new LinkedHashMap<>((Map<String, Object>) changed.get("metadata"));
        metadata.put("header", Map.of("A", "部门", "B", "余额"));
        changed.put("metadata", metadata);
        List<PreviewBlock> blocks = builder.preview(
            json.valueToTree(List.of(row("甲30", 1, false, true, "甲", "30"), changed)),
            "甲30\n\n乙60"
        );
        assertThat(blocks).hasSize(2);
        assertThat(blocks.getLast().table().columns()).extracting(PreviewColumn::label).contains("部门", "余额");
    }
}
