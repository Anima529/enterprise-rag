package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.support.parser.SourceMetadata;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** 将原生表格转为包含完整信息的行，不渲染或获取不可信内容。 */
final class TableNormalizer {

    private static final int MIN_INFERRED_TABLE_ROWS = 3;

    private static final int COLUMN_NAME_RADIX = 26;
    private static final int SUMMARY_TEXT_OVERHEAD_CHARACTERS = 64;

    private record Row(int number, Map<String, String> cells, Map<String, Object> position, boolean header) {}

    private record FixedLine(List<String> cells, List<Integer> starts) {}

    private final Parser markdown = Parser.builder()
        .extensions(List.of(TablesExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build();
    private int inferredTable;
    private final Map<String, Map<String, String>> rememberedHeaders = new HashMap<>();

    /** 按格式归一化表格结构，保持正文与表格的原始顺序。 */
    ParsedDocument normalize(ParsedDocument document, ParseBudget budget) throws Exception {
        List<ParsedBlock> blocks = document.blocks();
        List<String> warnings = new ArrayList<>(document.warnings());
        for (int index = 0; index < blocks.size(); ) {
            budget.check();
            ParsedBlock block = blocks.get(index);
            if (ParsedBlockProtocol.KEY_VALUE_SUMMARY.equals(block.metadata().get(SourceMetadata.KIND))) {
                keyValues(block, budget);
                index++;
            } else if (ParsedBlockProtocol.TABLE_CELL.equals(block.metadata().get(SourceMetadata.KIND))) {
                int end = index + 1;
                while (
                    end < blocks.size() &&
                    ParsedBlockProtocol.TABLE_CELL.equals(blocks.get(end).metadata().get(SourceMetadata.KIND)) &&
                    sameTable(block, blocks.get(end))
                ) {
                    end++;
                }
                word(blocks.subList(index, end), budget);
                index = end;
            } else if (block.metadata().containsKey("tableRows")) {
                int end = index + 1;
                while (
                    end < blocks.size() &&
                    blocks.get(end).metadata().containsKey("tableRows") &&
                    sameTable(block, blocks.get(end))
                ) {
                    end++;
                }
                excel(blocks.subList(index, end), budget);
                index = end;
            } else if (
                document.format() == DocumentFormat.MD &&
                ParsedBlockProtocol.TABLE.equals(block.metadata().get(SourceMetadata.KIND))
            ) {
                markdown(block, budget);
                index++;
            } else if (document.format() == DocumentFormat.TXT || document.format() == DocumentFormat.PDF) {
                if (
                    text(block, document.format() == DocumentFormat.PDF, budget) &&
                    document.format() == DocumentFormat.PDF &&
                    !warnings.contains(ParsedBlockProtocol.PDF_TABLES_INFERRED_FROM_ALIGNED_TEXT)
                ) {
                    warnings.add(ParsedBlockProtocol.PDF_TABLES_INFERRED_FROM_ALIGNED_TEXT);
                }
                index++;
            } else {
                budget.add(block);
                index++;
            }
        }
        return new ParsedDocument(document.format(), budget.blocks(), warnings);
    }

    /** 判断相邻表格块是否可按相同结构合并。 */
    private boolean sameTable(ParsedBlock left, ParsedBlock right) {
        return (
            Objects.equals(
                left.metadata().get(SourceMetadata.TABLE_INDEX),
                right.metadata().get(SourceMetadata.TABLE_INDEX)
            ) &&
            Objects.equals(
                left.metadata().get(SourceMetadata.SHEET_NAME),
                right.metadata().get(SourceMetadata.SHEET_NAME)
            )
        );
    }

    /** 将 Word 单元格块归组为保留表头和坐标的表格行。 */
    private void word(List<ParsedBlock> blocks, ParseBudget budget) {
        Map<Integer, Map<String, String>> cells = new TreeMap<>();
        Map<Integer, Map<String, Object>> positions = new TreeMap<>();
        Set<Integer> headers = new HashSet<>();
        Map<String, String> previous = new HashMap<>();
        int previousRow = 0;
        int width = 0;
        for (ParsedBlock block : blocks) {
            budget.check();
            Map<String, Object> source = block.metadata();
            int row = number(source, SourceMetadata.ROW_START, 1);
            int column = number(source, "cellIndex", 1);
            int span = number(source, "columnSpan", 1);
            width = Math.max(width, Math.max(number(source, "columnCount", 0), column + span - 1));
            budget.limit(width, budget.properties().maxColumns());
            if (row != previousRow) {
                if (previousRow > 0) {
                    previous.putAll(cells.get(previousRow));
                }
                previousRow = row;
            }
            Map<String, String> values = cells.computeIfAbsent(row, ignored -> new LinkedHashMap<>());
            String value = block.text();
            if (value.isBlank() && Boolean.TRUE.equals(source.get("verticalContinuation"))) {
                value = previous.getOrDefault(columnName(column), "");
            }
            if (value.isBlank() && Boolean.TRUE.equals(source.get("horizontalContinuation"))) {
                value = values.getOrDefault(columnName(column - 1), "");
            }
            for (int offset = 0; offset < span; offset++) {
                String key = columnName(column + offset);
                if (!value.isBlank()) {
                    values.merge(key, value, (old, next) -> old.isEmpty() ? next : old + "\n" + next);
                } else {
                    values.putIfAbsent(key, "");
                }
            }
            Map<String, Object> position = positions.computeIfAbsent(row, ignored -> new LinkedHashMap<>(source));
            position.put("paragraphEndIndex", number(source, "paragraphEndIndex", number(source, "paragraphIndex", 0)));
            if (Boolean.TRUE.equals(source.get("headerCell"))) {
                headers.add(row);
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> row : cells.entrySet()) {
            for (int column = 1; column <= width; column++) {
                row.getValue().putIfAbsent(columnName(column), "");
            }
            rows.add(
                new Row(row.getKey(), row.getValue(), positions.get(row.getKey()), headers.contains(row.getKey()))
            );
        }
        emit(blocks.getFirst(), rows, budget);
    }

    /** 将独立指标配对归一化为保留单元格来源的网格。 */
    private void keyValues(ParsedBlock source, ParseBudget budget) {
        if (!(source.metadata().get("summaryPairs") instanceof List<?> pairs)) {
            throw ParseBudget.invalid();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("A", "指标");
        headers.put("B", "数值");
        int row = number(source.metadata(), SourceMetadata.ROW_START, 0);
        for (Object value : pairs) {
            budget.check();
            if (!(value instanceof Map<?, ?> pair)) {
                throw ParseBudget.invalid();
            }
            Map<String, String> cells = new LinkedHashMap<>();
            cells.put("A", pair.get("label").toString());
            cells.put("B", pair.get("value").toString());
            String labelCell = pair.get("labelCell").toString();
            String valueCell = pair.get("valueCell").toString();
            Map<String, Object> metadata = new LinkedHashMap<>(source.metadata());
            metadata.remove("summaryPairs");
            metadata.put(SourceMetadata.KIND, ParsedBlockProtocol.TABLE_ROW);
            metadata.put(SourceMetadata.TABLE_LAYOUT, ParsedBlockProtocol.KEY_VALUE_SUMMARY);
            metadata.put("header", headers);
            metadata.put("tableCells", cells);
            metadata.put("tableHeader", false);
            metadata.put("headerRow", 0);
            metadata.put(SourceMetadata.HEADER_POLICY, ParsedBlockProtocol.KEY_VALUE_SUMMARY);
            metadata.put("sourceCellRefs", Map.of("A", labelCell, "B", valueCell));
            metadata.put("labelCellRange", pair.get("labelRange").toString());
            budget.pendingCharacters(
                (long) cells.get("A").length() +
                    cells.get("B").length() +
                    labelCell.length() +
                    valueCell.length() +
                    SUMMARY_TEXT_OVERHEAD_CHARACTERS
            );
            String text =
                "工作表：" +
                source.metadata().get(SourceMetadata.SHEET_NAME) +
                "\n第 " +
                row +
                " 行：指标 [" +
                labelCell +
                "]=" +
                cells.get("A") +
                "；数值 [" +
                valueCell +
                "]=" +
                cells.get("B");
            budget.add(new ParsedBlock(text, source.page(), source.chapter(), source.section(), metadata));
        }
    }

    /** 将工作表行组归一化并保留真实列与汇总结构。 */
    private void excel(List<ParsedBlock> blocks, ParseBudget budget) {
        List<Row> rows = new ArrayList<>();
        for (ParsedBlock block : blocks) {
            if (!(block.metadata().get("tableRows") instanceof List<?> values)) {
                continue;
            }
            for (Object value : values) {
                budget.check();
                if (
                    !(value instanceof Map<?, ?> record) ||
                    !(record.get(SourceMetadata.CELLS) instanceof Map<?, ?> cells)
                ) {
                    throw ParseBudget.invalid();
                }
                Map<String, String> columns = new LinkedHashMap<>();
                cells.forEach((key, cell) -> columns.put(key.toString(), cell.toString()));
                Map<String, Object> position = new LinkedHashMap<>(block.metadata());
                position.put(
                    SourceMetadata.MERGED_TITLE_ROW,
                    Boolean.TRUE.equals(record.get(SourceMetadata.MERGED_TITLE_ROW))
                );
                rows.add(
                    new Row(
                        ((Number) record.get(SourceMetadata.ROW)).intValue(),
                        columns,
                        position,
                        ParsedBlockProtocol.TABLE_HEADER.equals(block.metadata().get(SourceMetadata.KIND))
                    )
                );
            }
        }
        ParsedBlock first = blocks.getFirst();
        boolean inheritedHeader =
            !rows.isEmpty() &&
            ParsedBlockProtocol.ROW_GROUP.equals(first.metadata().get(SourceMetadata.KIND)) &&
            number(first.metadata(), "headerRow", 0) > 0 &&
            number(first.metadata(), "headerRow", 0) < rows.getFirst().number() &&
            first.metadata().get("header") instanceof Map<?, ?>;
        if (inheritedHeader && first.metadata().get("header") instanceof Map<?, ?> inherited) {
            Map<String, String> headers = new LinkedHashMap<>();
            inherited.forEach((column, label) -> headers.put(column.toString(), label.toString()));
            rememberedHeaders.putIfAbsent(tableIdentity(first), Collections.unmodifiableMap(headers));
        }
        // 独立标量或合并单元格中的句子不具有表头与数据行关系。
        // 将其保留为来源正文，避免填充空单元格形成虚构表格。
        if (!inheritedHeader && rows.size() == 1) {
            Row row = rows.getFirst();
            List<String> values = row
                .cells()
                .values()
                .stream()
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
            long populated = row
                .cells()
                .values()
                .stream()
                .filter(value -> !value.isBlank())
                .count();
            if (
                values.size() == 1 &&
                (populated == 1 || Boolean.TRUE.equals(row.position().get(SourceMetadata.MERGED_TITLE_ROW)))
            ) {
                Map<String, Object> metadata = new LinkedHashMap<>(row.position());
                metadata.remove("tableRows");
                metadata.remove("header");
                metadata.remove("headerRow");
                metadata.remove(SourceMetadata.MERGED_TITLE_ROW);
                metadata.remove(SourceMetadata.TABLE_INDEX);
                metadata.put(SourceMetadata.KIND, ParsedBlockProtocol.PARAGRAPH);
                metadata.put(SourceMetadata.ROW_START, row.number());
                metadata.put(SourceMetadata.ROW_END, row.number());
                Map<String, String> refs = new LinkedHashMap<>();
                row.cells().forEach((column, value) -> {
                    if (!value.isBlank()) {
                        refs.put(column, column + row.number());
                    }
                });
                metadata.put("sourceCellRefs", refs);
                budget.add(
                    new ParsedBlock(values.getFirst(), first.page(), first.chapter(), first.section(), metadata)
                );
                return;
            }
        }
        emit(first, rows, budget);
    }

    /** 将 Markdown 表格行解析为保留空单元格的网格。 */
    private void markdown(ParsedBlock block, ParseBudget budget) {
        String[] lines = block.text().split("\n", -1);
        Node root = markdown.parse(block.text());
        Node table = root.getFirstChild();
        if (!(table instanceof TableBlock)) {
            budget.add(block);
            return;
        }
        List<Row> rows = new ArrayList<>();
        int tableIndex = ++inferredTable;
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                budget.check();
                Map<String, String> cells = new LinkedHashMap<>();
                int column = 1;
                int sourceLine = -1;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    StringBuilder raw = new StringBuilder();
                    for (SourceSpan span : cell.getSourceSpans()) {
                        sourceLine = span.getLineIndex();
                        raw.append(lines[sourceLine], span.getColumnIndex(), span.getColumnIndex() + span.getLength());
                    }
                    cells.put(columnName(column++), raw.toString().strip().replace("\\|", "|"));
                }
                if (sourceLine < 0 && !row.getSourceSpans().isEmpty()) {
                    sourceLine = row.getSourceSpans().getFirst().getLineIndex();
                }
                Map<String, Object> position = new LinkedHashMap<>(block.metadata());
                int line = number(block.metadata(), SourceMetadata.LINE_START, 1) + Math.max(0, sourceLine);
                position.put(SourceMetadata.LINE_START, line);
                position.put(SourceMetadata.LINE_END, line);
                position.put(SourceMetadata.TABLE_INDEX, tableIndex);
                rows.add(new Row(rows.size() + 1, cells, position, section instanceof TableHead));
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
        metadata.put(SourceMetadata.TABLE_INDEX, tableIndex);
        emit(new ParsedBlock(block.text(), block.page(), block.chapter(), block.section(), metadata), rows, budget);
    }

    /** 识别文本中的分隔或对齐表格，保留周围普通正文。 */
    private boolean text(ParsedBlock block, boolean pdf, ParseBudget budget) throws Exception {
        String[] lines = block.text().split("\n", -1);
        int plain = 0;
        int index = 0;
        boolean detected = false;
        while (index < lines.length) {
            budget.check();
            char delimiter = tableDelimiter(lines[index], pdf);
            FixedLine fixed = fixedLine(lines[index]);
            boolean fixedWidth = !pdf && delimiter == 0 && fixed.cells().size() >= 2;
            List<String> first;
            if (fixedWidth) {
                first = fixed.cells();
            } else if (delimiter == 0) {
                first = List.of();
            } else {
                first = columns(lines[index], delimiter);
            }
            int end = index + 1;
            List<List<String>> grid = new ArrayList<>();
            grid.add(first);
            List<Integer> offsets = new ArrayList<>();
            offsets.add(index);
            while (first.size() >= 2 && end < lines.length) {
                if (delimiter == '|' && lines[end].strip().matches("\\+(?:[-=]+\\+)+")) {
                    end++;
                    continue;
                }
                if (!fixedWidth && lines[end].indexOf(delimiter) < 0) {
                    break;
                }
                FixedLine nextFixed = fixedLine(lines[end]);
                List<String> next = fixedWidth ? nextFixed.cells() : columns(lines[end], delimiter);
                if (pdf) {
                    next = alignedPdfCells(
                        block,
                        index,
                        end,
                        next,
                        first.size(),
                        budget.properties().pdfColumnAlignmentTolerance()
                    );
                }
                if (next.size() != first.size()) {
                    break;
                }
                if (fixedWidth && !fixed.starts().equals(nextFixed.starts())) {
                    break;
                }
                if (delimiter == '|' && next.stream().allMatch(cell -> cell.matches(":?[-=]{3,}:?"))) {
                    end++;
                    continue;
                }
                grid.add(next);
                offsets.add(end++);
            }
            boolean csvData =
                delimiter != ',' ||
                grid
                    .stream()
                    .skip(1)
                    .anyMatch(cells -> cells.stream().anyMatch(cell -> cell.matches("[-+]?\\d+(?:\\.\\d+)?")));
            if (
                first.size() < 2 ||
                grid.size() < (pdf || fixedWidth || delimiter == ',' ? MIN_INFERRED_TABLE_ROWS : 2) ||
                !csvData
            ) {
                index++;
                continue;
            }
            if (index > plain) {
                addPlain(block, lines, plain, index, budget);
            }
            List<Row> rows = new ArrayList<>();
            int table = ++inferredTable;
            for (int row = 0; row < grid.size(); row++) {
                Map<String, String> values = new LinkedHashMap<>();
                for (int column = 0; column < first.size(); column++) {
                    values.put(columnName(column + 1), grid.get(row).get(column));
                }
                Map<String, Object> position = textPosition(block, offsets.get(row), offsets.get(row) + 1);
                position.put(SourceMetadata.TABLE_INDEX, table);
                rows.add(new Row(row + 1, values, position, row == 0));
            }
            Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
            metadata.put(SourceMetadata.TABLE_INDEX, table);
            metadata.remove(SourceMetadata.COLUMN_STARTS_BY_LINE);
            emit(new ParsedBlock(block.text(), block.page(), block.chapter(), block.section(), metadata), rows, budget);
            detected = true;
            plain = end;
            index = end;
        }
        if (plain < lines.length) {
            addPlain(block, lines, plain, lines.length, budget);
        }
        return detected;
    }

    /** 按固定列边界提取单行单元格。 */
    private FixedLine fixedLine(String line) {
        List<String> cells = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        java.util.regex.Matcher gaps = java.util.regex.Pattern.compile(" {2,}").matcher(line);
        int start = 0;
        while (gaps.find()) {
            if (gaps.start() > start) {
                cells.add(line.substring(start, gaps.start()).strip());
                starts.add(start);
            }
            start = gaps.end();
        }
        if (start < line.length()) {
            cells.add(line.substring(start).strip());
            starts.add(start);
        }
        return new FixedLine(cells, starts);
    }

    /** 结合 PDF 列定位判断可对齐的单元格范围。 */
    private List<String> alignedPdfCells(
        ParsedBlock block,
        int first,
        int next,
        List<String> cells,
        int columns,
        double tolerance
    ) {
        if (
            !(block.metadata().get(SourceMetadata.COLUMN_STARTS_BY_LINE) instanceof List<?> starts) ||
            next >= starts.size() ||
            cells.size() < 2
        ) {
            return List.of();
        }
        if (
            !(starts.get(first) instanceof List<?> left) ||
            !(starts.get(next) instanceof List<?> right) ||
            left.size() != columns ||
            right.size() != cells.size()
        ) {
            return List.of();
        }
        List<String> aligned = new ArrayList<>(Collections.nCopies(columns, ""));
        int column = 0;
        for (int index = 0; index < cells.size(); index++) {
            double anchor = ((Number) right.get(index)).doubleValue();
            while (column < columns && ((Number) left.get(column)).doubleValue() < anchor - tolerance) {
                column++;
            }
            if (column == columns || Math.abs(((Number) left.get(column)).doubleValue() - anchor) > tolerance) {
                return List.of();
            }
            aligned.set(column++, cells.get(index));
        }
        return aligned;
    }

    /** 按分隔符拆分单行内容，保留空列和 CSV 引号规则。 */
    private List<String> columns(String line, char delimiter) throws Exception {
        if (delimiter == '|') {
            line = line.strip();
            if (line.startsWith("|")) {
                line = line.substring(1);
            }
            if (line.endsWith("|") && !line.endsWith("\\|")) {
                line = line.substring(0, line.length() - 1);
            }
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .setIgnoreSurroundingSpaces(true)
            .setQuote(delimiter == '|' ? null : '"')
            .setEscape(delimiter == '|' ? '\\' : null)
            .get();
        try (CSVParser parser = CSVParser.parse(line, format)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.size() != 1) {
                return List.of();
            }
            List<String> cells = new ArrayList<>();
            records.getFirst().forEach(cells::add);
            return cells;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    /** 保存未归入表格的普通文本及来源范围。 */
    private void addPlain(ParsedBlock source, String[] lines, int start, int end, ParseBudget budget) {
        budget.add(
            new ParsedBlock(
                String.join("\n", Arrays.copyOfRange(lines, start, end)),
                source.page(),
                source.chapter(),
                source.section(),
                textPosition(source, start, end)
            )
        );
    }

    /** 将文本片段范围映射回原块的行号与定位信息。 */
    private Map<String, Object> textPosition(ParsedBlock source, int start, int end) {
        Map<String, Object> position = new LinkedHashMap<>(source.metadata());
        position.remove(SourceMetadata.COLUMN_STARTS_BY_LINE);
        int first = number(source.metadata(), SourceMetadata.LINE_START, 1);
        position.put(SourceMetadata.LINE_START, first + start);
        position.put(SourceMetadata.LINE_END, first + end - 1);
        return position;
    }

    /** 输出归一化表格行和来源元数据，遵守输出预算。 */
    private void emit(ParsedBlock source, List<Row> rows, ParseBudget budget) {
        if (rows.isEmpty() || rows.stream().allMatch(row -> row.cells().values().stream().allMatch(String::isBlank))) {
            return;
        }
        Set<String> columns = new TreeSet<>(Comparator.comparingInt(TableNormalizer::columnNumber));
        rows.forEach(row -> columns.addAll(row.cells().keySet()));
        budget.limit(columns.size(), budget.properties().maxColumns());
        boolean explicitHeader =
            ParsedBlockProtocol.TABLE.equals(source.metadata().get(SourceMetadata.KIND)) ||
            (ParsedBlockProtocol.TABLE_CELL.equals(source.metadata().get(SourceMetadata.KIND)) &&
                rows.getFirst().header());
        boolean numericFirstRow = rows
            .getFirst()
            .cells()
            .values()
            .stream()
            .anyMatch(
                value ->
                    value.strip().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]+)?") ||
                    value.startsWith("[公式无缓存] =")
            );
        int headerCount = !explicitHeader && (rows.size() == 1 || numericFirstRow) ? 0 : 1;
        while (headerCount > 0 && headerCount < rows.size() && rows.get(headerCount).header()) {
            headerCount++;
        }
        if (rows.size() > 1 && Boolean.TRUE.equals(rows.getFirst().position().get(SourceMetadata.MERGED_TITLE_ROW))) {
            headerCount = Math.max(headerCount, 2);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String column : columns) {
            List<String> labels = new ArrayList<>();
            for (int row = 0; row < headerCount; row++) {
                String label = rows.get(row).cells().getOrDefault(column, "");
                if (!label.isBlank() && !labels.contains(label)) {
                    labels.add(label);
                }
            }
            headers.put(column, labels.isEmpty() ? "列 " + column : String.join(" / ", labels));
        }
        String identity = tableIdentity(source);
        Map<String, String> remembered = rememberedHeaders.putIfAbsent(
            identity,
            Collections.unmodifiableMap(new LinkedHashMap<>(headers))
        );
        if (remembered != null) {
            headers.clear();
            headers.putAll(remembered);
            columns.addAll(remembered.keySet());
            budget.limit(columns.size(), budget.properties().maxColumns());
            for (String column : columns) {
                headers.putIfAbsent(column, "列 " + column);
            }
            headerCount = 0;
        }
        for (int index = 0; index < rows.size(); index++) {
            budget.check();
            Row row = rows.get(index);
            Map<String, String> cells = new LinkedHashMap<>();
            List<String> fields = new ArrayList<>();
            long characters = 0;
            for (String column : columns) {
                String value = row.cells().getOrDefault(column, "");
                cells.put(column, value);
                String field =
                    headers.get(column).replace('\n', ' ') +
                    " [" +
                    column +
                    "]=" +
                    (value.isEmpty() ? "（空）" : value.replace("\n", " / "));
                characters += field.length() + 1L;
                budget.pendingCharacters(characters);
                fields.add(field);
            }
            Map<String, Object> metadata = new LinkedHashMap<>(source.metadata());
            metadata.putAll(row.position());
            for (String key : List.of(
                "tableRows",
                SourceMetadata.COLUMN_STARTS_BY_LINE,
                "cellIndex",
                "columnSpan",
                "columnCount",
                "verticalContinuation",
                "horizontalContinuation",
                "headerCell"
            )) {
                metadata.remove(key);
            }
            metadata.put(SourceMetadata.KIND, ParsedBlockProtocol.TABLE_ROW);
            metadata.put(SourceMetadata.TABLE_INDEX, source.metadata().get(SourceMetadata.TABLE_INDEX));
            metadata.put("header", headers);
            metadata.put("tableCells", cells);
            metadata.put("tableHeader", index < headerCount);
            metadata.put(
                SourceMetadata.ROW_ROLE,
                index < headerCount ? ParsedBlockProtocol.HEADER : ParsedBlockProtocol.DATA
            );
            metadata.put("headerRow", number(source.metadata(), "headerRow", rows.getFirst().number()));
            metadata.put(SourceMetadata.ROW_START, row.number());
            metadata.put(SourceMetadata.ROW_END, row.number());
            metadata.put(SourceMetadata.HEADER_POLICY, headerPolicy(remembered != null, headerCount, explicitHeader));
            String context =
                metadata.get(SourceMetadata.SHEET_NAME) instanceof String sheet ? "工作表：" + sheet + "\n" : "";
            String text = context + "第 " + row.number() + " 行：" + String.join("；", fields);
            budget.add(new ParsedBlock(text, source.page(), source.chapter(), source.section(), metadata));
        }
    }

    /** 识别文本行使用的制表符、竖线、CSV 或对齐表格分隔形式。 */
    private char tableDelimiter(String line, boolean pdf) {
        if (line.contains("\t")) {
            return '\t';
        }
        if (line.contains("|")) {
            return '|';
        }
        if (!pdf && line.contains(",")) {
            return ',';
        }
        return 0;
    }

    /** 根据显式结构决定表头展示策略。 */
    private String headerPolicy(boolean inheritedHeader, int headerCount, boolean explicitHeader) {
        if (inheritedHeader) {
            return ParsedBlockProtocol.INHERITED_TABLE_HEADER;
        }
        if (headerCount == 0) {
            return ParsedBlockProtocol.PHYSICAL_COLUMNS;
        }
        if (explicitHeader) {
            return ParsedBlockProtocol.EXPLICIT_HEADER;
        }
        return ParsedBlockProtocol.INFERRED_FIRST_ROW;
    }

    /** 构造来源内的表格标识以区分不同网格。 */
    private String tableIdentity(ParsedBlock source) {
        return (
            source.metadata().get(SourceMetadata.SHEET_NAME) + "/" + source.metadata().get(SourceMetadata.TABLE_INDEX)
        );
    }

    /** 将列号转换为可读的 Excel 列名。 */
    static String columnName(int column) {
        StringBuilder name = new StringBuilder();
        while (column > 0) {
            column--;
            name.insert(0, (char) ('A' + (column % COLUMN_NAME_RADIX)));
            column /= COLUMN_NAME_RADIX;
        }
        return name.toString();
    }

    /** 将 Excel 列字母转换为列号，供网格按原始列序排列。 */
    private static int columnNumber(String column) {
        int columnNumber = 0;
        for (char value : column.toCharArray()) {
            columnNumber = columnNumber * COLUMN_NAME_RADIX + value - 'A' + 1;
        }
        return columnNumber;
    }

    /** 从元数据读取整数位置，缺失时使用指定默认值。 */
    private static int number(Map<String, Object> values, String key, int fallback) {
        return values.get(key) instanceof Number number ? number.intValue() : fallback;
    }
}
