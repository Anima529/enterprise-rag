package com.example.rag.document.service.preview;

import static com.example.rag.document.dto.preview.SourcePreviewModels.*;

import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.support.parser.SourceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

/** 完整表格行使用持久化结构，其余内容保留原始来源片段。 */
@Component
public class SourcePreviewBuilder {

    private record Key(String sheet, Integer index, Map<String, String> headers) {}

    private static final class Grid {

        final Key key;
        final List<PreviewRow> rows = new ArrayList<>();
        boolean truncated;

        /** 登记预览网格标识，供兼容表格行归组使用。 */
        Grid(Key key) {
            this.key = key;
        }

        /** 将已收集的可见表格行转换为统一预览块。 */
        PreviewBlock block() {
            List<PreviewColumn> columns = key
                .headers()
                .entrySet()
                .stream()
                .map(entry -> new PreviewColumn(entry.getKey(), entry.getValue()))
                .toList();
            return new PreviewBlock(
                PreviewType.TABLE,
                null,
                new PreviewTable(key.sheet(), key.index(), columns, List.copyOf(rows), truncated),
                truncated
            );
        }
    }

    /** 在引用片段边界内生成有序文本与表格预览。 */
    public List<PreviewBlock> preview(JsonNode sources, String content) {
        return build(sources, content, content.length());
    }

    /** 为仅支持单个完整表格展示的旧客户端保留兼容字段。 */
    public List<PreviewTable> tables(JsonNode sources, int includedCharacters) {
        List<PreviewBlock> blocks = build(sources, null, Math.max(0, includedCharacters));
        if (blocks.stream().anyMatch(block -> block.type() != PreviewType.TABLE)) {
            return List.of();
        }
        return blocks.stream().map(PreviewBlock::table).toList();
    }

    /** 收集可见来源片段，合并兼容网格并保留正文顺序。 */
    private List<PreviewBlock> build(JsonNode sources, String content, int limit) {
        if (!sources.isArray() || sources.isEmpty()) {
            return plain(content);
        }
        List<PreviewBlock> previewBlocks = new ArrayList<>();
        Grid grid = null;
        long offset = 0;
        for (JsonNode source : sources) {
            JsonNode metadata = source.path(SourceMetadata.METADATA);
            if (
                !source.path(SourceMetadata.CHAR_START).isInt() ||
                !source.path(SourceMetadata.CHAR_END).isInt() ||
                source.path(SourceMetadata.CHAR_START).intValue() < 0 ||
                source.path(SourceMetadata.CHAR_END).intValue() <= source.path(SourceMetadata.CHAR_START).intValue()
            ) {
                if (grid != null) {
                    previewBlocks.add(grid.block());
                }
                addText(previewBlocks, slice(content, offset, limit), false);
                return List.copyOf(previewBlocks);
            }
            long end =
                offset +
                (long) source.path(SourceMetadata.CHAR_END).intValue() -
                source.path(SourceMetadata.CHAR_START).intValue();
            boolean table = ParsedBlockProtocol.TABLE_ROW.equals(metadata.path(SourceMetadata.KIND).asText());
            Key key = table ? key(metadata) : null;
            if (offset >= limit) {
                if (grid != null && key != null && grid.key.equals(key)) {
                    grid.truncated = true;
                }
                offset = end + 2;
                continue;
            }
            boolean complete =
                table && key != null && metadata.path("tableRowComplete").asBoolean(false) && end <= limit;
            if (complete) {
                if (grid == null || !grid.key.equals(key)) {
                    if (grid != null) {
                        previewBlocks.add(grid.block());
                    }
                    grid = new Grid(key);
                }
                if (!metadata.path("tableHeader").asBoolean()) {
                    List<String> cells = key
                        .headers()
                        .keySet()
                        .stream()
                        .map(column -> metadata.path("tableCells").path(column).asText(""))
                        .toList();
                    grid.rows.add(new PreviewRow(metadata.path(SourceMetadata.ROW_START).asInt(), cells));
                }
            } else {
                if (grid != null) {
                    if (table && key != null && grid.key.equals(key)) {
                        grid.truncated = true;
                    }
                    previewBlocks.add(grid.block());
                    grid = null;
                }
                addText(previewBlocks, slice(content, offset, Math.min(end, limit)), table);
            }
            offset = end + 2;
        }
        if (grid != null) {
            previewBlocks.add(grid.block());
        }
        if (content != null && offset < limit) {
            addText(previewBlocks, slice(content, offset, limit), false);
        }
        return List.copyOf(previewBlocks);
    }

    /** 结合表格标识和列定义区分来源网格。 */
    private Key key(JsonNode metadata) {
        JsonNode header = metadata.path("header");
        JsonNode cells = metadata.path("tableCells");
        if (!header.isObject() || header.isEmpty() || !cells.isObject()) {
            return null;
        }
        Map<String, String> columns = new LinkedHashMap<>();
        header.properties().forEach(entry -> columns.put(entry.getKey(), entry.getValue().asText()));
        // 旧表头定义遗漏列时，仍保留额外的物理单元格。
        cells.properties().forEach(entry -> columns.putIfAbsent(entry.getKey(), "列 " + entry.getKey()));
        return new Key(
            metadata.hasNonNull(SourceMetadata.SHEET_NAME) ? metadata.path(SourceMetadata.SHEET_NAME).asText() : null,
            metadata.path(SourceMetadata.TABLE_INDEX).isInt()
                ? metadata.path(SourceMetadata.TABLE_INDEX).intValue()
                : null,
            Collections.unmodifiableMap(columns)
        );
    }

    /** 在可见字符范围内截取表格行，隐藏未提供的单元格值。 */
    private String slice(String content, long from, long to) {
        return content == null
            ? ""
            : content.substring((int) Math.min(from, content.length()), (int) Math.min(to, content.length()));
    }

    /** 将可见普通文本追加到预览并合并相邻文本片段。 */
    private void addText(List<PreviewBlock> output, String text, boolean truncated) {
        // 只有旧版 tables() 适配器使用空正文；混合证据仍保留结构标记。
        output.add(new PreviewBlock(PreviewType.TEXT, text, null, truncated));
    }

    /** 为缺少结构化元数据的来源构造纯文本预览。 */
    private List<PreviewBlock> plain(String content) {
        return content == null || content.isEmpty()
            ? List.of()
            : List.of(new PreviewBlock(PreviewType.TEXT, content, null, false));
    }
}
