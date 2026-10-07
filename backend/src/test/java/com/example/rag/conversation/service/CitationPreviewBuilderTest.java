package com.example.rag.conversation.service;

import static com.example.rag.conversation.dto.ConversationModels.*;
import static org.assertj.core.api.Assertions.*;

import com.example.rag.document.dto.preview.SourcePreviewModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitationPreviewBuilderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CitationPreviewBuilder builder = new CitationPreviewBuilder(
        json,
        new com.example.rag.document.service.preview.SourcePreviewBuilder()
    );

    /** 构造保留列定义与来源位置的表格测试行。 */
    private Map<String, Object> row(
        String text,
        int number,
        boolean header,
        boolean complete,
        String city,
        String rate
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("A", "城市类别");
        headers.put("B", "住宿上限");
        return Map.of(
            "charStart",
            0,
            "charEnd",
            text.length(),
            "metadata",
            Map.of(
                "kind",
                "TABLE_ROW",
                "tableIndex",
                1,
                "tableRowComplete",
                complete,
                "tableHeader",
                header,
                "header",
                headers,
                "tableCells",
                Map.of("A", city, "B", rate),
                "rowStart",
                number
            )
        );
    }

    /** 验证表头只显示一次，列顺序、空单元格及原始值保持不变。 */
    @Test
    void shouldRenderHeaderOnceAndPreserveColumnOrderEmptyCellsAndOriginalValues() {
        JsonNode sources = json.valueToTree(
            List.of(
                row("表头", 1, true, true, "城市类别", "住宿上限"),
                row("北京600", 2, false, true, "北京", "600"),
                row("空值", 3, false, true, "其他", "")
            )
        );
        List<PreviewTable> tables = builder.tables(sources, 100);
        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst().columns())
            .extracting(PreviewColumn::label)
            .containsExactly("城市类别", "住宿上限");
        assertThat(tables.getFirst().rows())
            .extracting(PreviewRow::cells)
            .containsExactly(List.of("北京", "600"), List.of("其他", ""));
        assertThat(tables.getFirst().truncated()).isFalse();
    }

    /** 验证引用不会泄露未完整提供或片段之外的表格行。 */
    @Test
    void shouldExcludePartialOrUnprovidedRowsInsteadOfLeakingWholeStoredTable() {
        JsonNode sources = json.valueToTree(
            List.of(
                row("表头", 1, true, true, "城市类别", "住宿上限"),
                row("北京600", 2, false, true, "北京", "600"),
                row("深圳700", 3, false, true, "深圳", "700")
            )
        );
        PreviewTable table = builder.tables(sources, 9).getFirst();
        assertThat(table.rows()).extracting(PreviewRow::cells).containsExactly(List.of("北京", "600"));
        assertThat(table.truncated()).isTrue();
        assertThat(builder.tables(sources, 8)).isEmpty();
        JsonNode partial = json.valueToTree(List.of(row("部分北京", 2, false, false, "北京", "600")));
        assertThat(builder.tables(partial, 100)).isEmpty();
    }

    /** 验证普通或混合证据保留可读正文。 */
    @Test
    void shouldKeepPlainOrMixedEvidenceAsText() {
        JsonNode sources = json.valueToTree(
            List.of(
                row("北京600", 2, false, true, "北京", "600"),
                Map.of("charStart", 0, "charEnd", 2, "metadata", Map.of("kind", "PARAGRAPH"))
            )
        );
        assertThat(builder.tables(sources, 100)).isEmpty();
        assertThat(builder.tables(json.createArrayNode(), 100)).isEmpty();
    }

    /** 验证无实时索引时仍恢复快照，有来源时兼容升级旧预览。 */
    @Test
    void shouldRestorePersistedSnapshotWithoutLiveIndexAndUpgradeLegacyPreviewWhenAvailable() throws Exception {
        JsonNode sources = json.valueToTree(List.of(row("北京600", 2, false, true, "北京", "600")));
        List<PreviewTable> tables = builder.tables(sources, 5);
        CitationSnapshot saved = new CitationSnapshot(
            "C1",
            3,
            1,
            "制度.md",
            null,
            null,
            null,
            1,
            2,
            8,
            "北京600",
            json.writeValueAsString(tables),
            "[]",
            null
        );
        assertThat(builder.restore(saved).tables()).isEqualTo(tables);
        CitationSnapshot legacy = new CitationSnapshot(
            "C1",
            3,
            1,
            "制度.md",
            null,
            null,
            null,
            1,
            2,
            8,
            "北京600",
            "[]",
            "[]",
            json.writeValueAsString(Map.of("sources", sources))
        );
        assertThat(builder.restore(legacy).tables()).isEqualTo(tables);
        CitationSnapshot cleaned = new CitationSnapshot(
            "C1",
            3,
            1,
            "制度.md",
            null,
            null,
            null,
            1,
            2,
            8,
            "北京600",
            "[]",
            "[]",
            null
        );
        assertThat(builder.restore(cleaned).tables()).isEmpty();
    }

    /** 验证混合快照恢复后来源顺序和原始内容不变。 */
    @Test
    void shouldRestoreOrderedMixedSnapshotWithoutIndexAndNeverChangeItsOriginalSource() throws Exception {
        JsonNode sources = json.valueToTree(
            List.of(
                row("北京600", 2, false, true, "北京", "600"),
                Map.of("charStart", 0, "charEnd", 2, "metadata", Map.of("kind", "PARAGRAPH"))
            )
        );
        List<PreviewBlock> preview = builder.preview(sources, "北京600\n\n备注");
        CitationSnapshot snapshot = new CitationSnapshot(
            "C1",
            3,
            1,
            "制度.md",
            null,
            null,
            null,
            1,
            2,
            8,
            "北京600\n\n备注",
            "[]",
            json.writeValueAsString(preview),
            null
        );
        assertThat(builder.restore(snapshot).preview()).isEqualTo(preview);
        assertThat(builder.restore(snapshot).preview())
            .extracting(PreviewBlock::type)
            .containsExactly(PreviewType.TABLE, PreviewType.TEXT);
        assertThat(builder.restore(snapshot).preview().getLast().text()).isEqualTo("备注");
    }
}
