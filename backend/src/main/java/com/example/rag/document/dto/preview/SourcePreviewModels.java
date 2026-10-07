package com.example.rag.document.dto.preview;

import java.util.List;

/** 有序且有长度限制的来源展示结构，由文档详情和引用共同使用。 */
public final class SourcePreviewModels {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private SourcePreviewModels() {
    }

    public enum PreviewType {
        TEXT,
        TABLE,
    }

    public record PreviewColumn(String key, String label) {}

    public record PreviewRow(int rowNumber, List<String> cells) {}

    public record PreviewTable(
        String sheetName,
        Integer tableIndex,
        List<PreviewColumn> columns,
        List<PreviewRow> rows,
        boolean truncated
    ) {}

    public record PreviewBlock(PreviewType type, String text, PreviewTable table, boolean truncated) {}
}
