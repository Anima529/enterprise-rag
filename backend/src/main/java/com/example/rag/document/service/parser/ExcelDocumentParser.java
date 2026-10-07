package com.example.rag.document.service.parser;

import static com.example.rag.document.service.parser.OfficeArchive.*;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.support.parser.SourceMetadata;
import java.io.InputStream;
import java.util.*;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final int RANGE_LAST_COLUMN_GROUP = 3;
    private static final int RANGE_LAST_ROW_GROUP = 4;

    private static final int COLUMN_NAME_RADIX = 26;
    private static final int ROW_TEXT_OVERHEAD_CHARACTERS = 32;
    private static final int CELL_TEXT_OVERHEAD_CHARACTERS = 16;
    private static final int SUMMARY_TEXT_OVERHEAD_CHARACTERS = 64;

    private static final java.util.regex.Pattern SUMMARY_LABEL = java.util.regex.Pattern.compile(
        "^(?:合计|总计|小计|汇总|TOTAL|SUBTOTAL|GRAND\\s+TOTAL)(?:$|[\\s:：（(].*)"
    );
    private static final java.util.regex.Pattern NUMERIC_VALUE = java.util.regex.Pattern.compile(
        "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]+)?"
    );
    private static final java.util.regex.Pattern SUMMARY_METRIC = java.util.regex.Pattern.compile(
        "合计|总计|小计|汇总|数量|总数|总额|TOTAL|SUBTOTAL|COUNT"
    );

    private record Sheet(String name, String relationship) {}

    private record Cell(int column, String value) {}

    private record Merge(int firstRow, int lastRow, int firstColumn, int lastColumn) {}

    private record SummaryPair(int labelFirst, int labelLast, int valueColumn, String label, String value) {}

    /** 声明解析器支持 XLSX 格式。 */
    public DocumentFormat format() {
        return DocumentFormat.XLSX;
    }

    /** 在解析预算内提取 XLSX 内容并保留结构与来源位置。 */
    public ParsedDocument parse(InputStream input, ParseBudget budget) throws Exception {
        try (OfficeArchive archive = new OfficeArchive(format(), input, budget)) {
            List<String> strings = sharedStrings(archive, budget);
            Map<String, String> relationships = relationships(archive, budget);
            List<Sheet> sheets = sheets(archive, budget);
            long[] cells = { 0 };
            for (Sheet sheet : sheets) {
                String target = relationships.get(sheet.relationship());
                if (target == null) {
                    throw ParseBudget.invalid();
                }
                worksheet(archive, target, sheet.name(), strings, cells, budget);
            }
        }
        return new ParsedDocument(format(), budget.blocks(), List.of());
    }

    /** 按工作簿顺序读取工作表名称及关系 ID。 */
    private List<Sheet> sheets(OfficeArchive archive, ParseBudget budget) throws Exception {
        List<Sheet> sheets = new ArrayList<>();
        XMLStreamReader xml = archive.xml("xl/workbook.xml");
        try {
            while (xml.hasNext()) {
                budget.check();
                xml.next();
                if (start(xml, SHEET, "sheet")) {
                    budget.limit(sheets.size() + 1L, budget.properties().maxSheets());
                    sheets.add(new Sheet(xml.getAttributeValue(null, "name"), xml.getAttributeValue(REL, "id")));
                }
            }
        } finally {
            xml.close();
        }
        return sheets;
    }

    /** 解析工作表内部关系，拒绝外部目标和越界路径。 */
    private Map<String, String> relationships(OfficeArchive archive, ParseBudget budget) throws Exception {
        Map<String, String> relationships = new HashMap<>();
        XMLStreamReader xml = archive.xml("xl/_rels/workbook.xml.rels");
        try {
            while (xml.hasNext()) {
                budget.check();
                xml.next();
                if (
                    start(xml, "http://schemas.openxmlformats.org/package/2006/relationships", "Relationship") &&
                    (REL + "/worksheet").equals(xml.getAttributeValue(null, "Type"))
                ) {
                    if ("External".equalsIgnoreCase(xml.getAttributeValue(null, "TargetMode"))) {
                        throw ParseBudget.invalid();
                    }
                    String target = xml.getAttributeValue(null, "Target");
                    if (target == null || target.contains(":") || target.contains("\\")) {
                        throw ParseBudget.invalid();
                    }
                    String part = target.startsWith("/")
                        ? target.substring(1)
                        : PathNormalizer.normalize("xl/" + target);
                    if (!part.startsWith("xl/worksheets/") || !part.endsWith(".xml")) {
                        throw ParseBudget.invalid();
                    }
                    String id = xml.getAttributeValue(null, "Id");
                    if (relationships.putIfAbsent(id, part) != null) {
                        throw ParseBudget.invalid();
                    }
                }
            }
        } finally {
            xml.close();
        }
        return relationships;
    }

    /** 读取共享文本并排除拼音标注，限制累计字符量。 */
    private List<String> sharedStrings(OfficeArchive archive, ParseBudget budget) throws Exception {
        List<String> strings = new ArrayList<>();
        if (!archive.has("xl/sharedStrings.xml")) {
            return strings;
        }
        XMLStreamReader xml = archive.xml("xl/sharedStrings.xml");
        StringBuilder value = new StringBuilder();
        long characters = 0;
        boolean phonetic = false;
        try {
            while (xml.hasNext()) {
                budget.check();
                xml.next();
                if (start(xml, SHEET, "si")) {
                    value.setLength(0);
                }
                if (start(xml, SHEET, "rPh")) {
                    phonetic = true;
                }
                if (end(xml, SHEET, "rPh")) {
                    phonetic = false;
                }
                if (start(xml, SHEET, "t") && !phonetic) {
                    value.append(OfficeArchive.text(xml, budget));
                }
                if (end(xml, SHEET, "si")) {
                    budget.limit(strings.size() + 1L, budget.properties().maxSharedStrings());
                    characters += value.length();
                    budget.limit(characters, budget.documents().maxTextCharacters());
                    strings.add(value.toString());
                }
            }
        } finally {
            xml.close();
        }
        return strings;
    }

    /** 按物理行处理工作表，保留合并单元格、表头与独立汇总片段。 */
    private void worksheet(
        OfficeArchive archive,
        String part,
        String name,
        List<String> strings,
        long[] cells,
        ParseBudget budget
    ) throws Exception {
        XMLStreamReader xml = archive.xml(part);
        List<Merge> merges = merges(archive, part, budget);
        Map<Merge, String> mergedValues = new HashMap<>();
        Map<Integer, String> headers = new TreeMap<>();
        Map<Integer, String> previousHeaders = new TreeMap<>();
        List<String> group = new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();
        long groupCharacters = 0;
        int row = 0;
        int previous = 0;
        int first = 0;
        int last = 0;
        int headerRow = 0;
        int previousHeaderRow = 0;
        int tableIndex = 1;
        try {
            while (xml.hasNext()) {
                budget.check();
                xml.next();
                if (!start(xml, SHEET, SourceMetadata.ROW)) {
                    continue;
                }
                String number = xml.getAttributeValue(null, "r");
                row = number == null ? previous + 1 : Integer.parseInt(number);
                if (row <= previous) {
                    throw ParseBudget.invalid();
                }
                budget.limit(row, budget.properties().maxRowsPerSheet());
                previous = row;
                Map<Integer, String> values = row(xml, row, strings, cells, budget);
                for (Merge merge : merges) {
                    budget.check();
                    if (merge.firstRow() == row) {
                        mergedValues.put(merge, values.getOrDefault(merge.firstColumn(), ""));
                    }
                    if (merge.firstRow() <= row && row <= merge.lastRow()) {
                        String origin = mergedValues.getOrDefault(merge, "");
                        for (int column = merge.firstColumn(); column <= merge.lastColumn(); column++) {
                            if (values.getOrDefault(column, "").isEmpty()) {
                                values.put(column, origin);
                            }
                        }
                    }
                }
                if (values.values().stream().allMatch(String::isBlank)) {
                    if (!group.isEmpty()) {
                        addGroup(
                            name,
                            headers,
                            headerRow,
                            first,
                            last,
                            group,
                            records,
                            ParsedBlockProtocol.ROW_GROUP,
                            tableIndex,
                            budget
                        );
                    }
                    if (headerRow > 0) {
                        previousHeaders.clear();
                        previousHeaders.putAll(headers);
                        previousHeaderRow = headerRow;
                        tableIndex++;
                    }
                    headers.clear();
                    group.clear();
                    records.clear();
                    groupCharacters = 0;
                    headerRow = 0;
                    continue;
                }
                List<SummaryPair> pairs = summaryPairs(values);
                if (!pairs.isEmpty()) {
                    if (!group.isEmpty()) {
                        addGroup(
                            name,
                            headers,
                            headerRow,
                            first,
                            last,
                            group,
                            records,
                            ParsedBlockProtocol.ROW_GROUP,
                            tableIndex,
                            budget
                        );
                    }
                    if (headerRow > 0) {
                        tableIndex++;
                    }
                    addSummary(name, row, tableIndex++, pairs, budget);
                    headers.clear();
                    previousHeaders.clear();
                    group.clear();
                    records.clear();
                    groupCharacters = 0;
                    headerRow = 0;
                    previousHeaderRow = 0;
                    continue;
                }
                // 数值汇总前的空白分隔行不能使汇总行被识别为新表头。
                // 保留独立片段，让完整汇总行在有长度限制的引用中仍可展示。
                if (headerRow == 0 && previousHeaderRow > 0 && summary(values)) {
                    headers.putAll(previousHeaders);
                    headerRow = previousHeaderRow;
                }
                if (headerRow == 0) {
                    headerRow = row;
                    for (Map.Entry<Integer, String> value : values.entrySet()) {
                        headers.put(
                            value.getKey(),
                            value.getValue().isBlank() ? "列 " + columnName(value.getKey()) : value.getValue()
                        );
                    }
                    addGroup(
                        name,
                        headers,
                        row,
                        row,
                        row,
                        List.of("表头：" + String.join(" | ", headers.values())),
                        List.of(record(row, values, merges)),
                        ParsedBlockProtocol.TABLE_HEADER,
                        tableIndex,
                        budget
                    );
                } else {
                    if (group.isEmpty()) {
                        first = row;
                    }
                    last = row;
                    List<String> fields = new ArrayList<>();
                    Set<Integer> columns = new TreeSet<>(headers.keySet());
                    columns.addAll(values.keySet());
                    long rowCharacters = ROW_TEXT_OVERHEAD_CHARACTERS;
                    for (int column : columns) {
                        String header = headers.getOrDefault(column, "列 " + columnName(column));
                        String value = values.getOrDefault(column, "");
                        rowCharacters += (long) header.length() + value.length() + CELL_TEXT_OVERHEAD_CHARACTERS;
                        budget.pendingCharacters(groupCharacters + rowCharacters);
                        fields.add(header + " [" + columnName(column) + "]=" + value);
                    }
                    String rowText = "第 " + row + " 行：" + String.join("；", fields);
                    groupCharacters += rowText.length() + 1L;
                    group.add(rowText);
                    records.add(record(row, values, merges));
                    if (group.size() >= budget.properties().excelRowGroupSize()) {
                        addGroup(
                            name,
                            headers,
                            headerRow,
                            first,
                            last,
                            group,
                            records,
                            ParsedBlockProtocol.ROW_GROUP,
                            tableIndex,
                            budget
                        );
                        group.clear();
                        records.clear();
                        groupCharacters = 0;
                    }
                }
            }
            if (!group.isEmpty()) {
                addGroup(
                    name,
                    headers,
                    headerRow,
                    first,
                    last,
                    group,
                    records,
                    ParsedBlockProtocol.ROW_GROUP,
                    tableIndex,
                    budget
                );
            }
        } finally {
            xml.close();
        }
    }

    /** 判断行是否为带数值的汇总行。 */
    private boolean summary(Map<Integer, String> values) {
        List<String> populated = values
            .values()
            .stream()
            .filter(value -> !value.isBlank())
            .toList();
        return (
            populated.size() > 1 &&
            SUMMARY_LABEL.matcher(populated.getFirst().strip().toUpperCase(Locale.ROOT)).matches() &&
            populated.stream().skip(1).anyMatch(this::numericData)
        );
    }

    /** 判断单元格是否包含可识别的数值内容。 */
    private boolean numericData(String value) {
        return NUMERIC_VALUE.matcher(value.strip()).matches() || value.startsWith("[公式无缓存] =");
    }

    /** 识别同一物理行中的横向指标与数值配对。 */
    private List<SummaryPair> summaryPairs(Map<Integer, String> values) {
        List<SummaryPair> pairs = new ArrayList<>();
        String label = null;
        int first = 0;
        int last = 0;
        for (Map.Entry<Integer, String> cell : values.entrySet()) {
            String value = cell.getValue();
            if (value.isBlank()) {
                continue;
            }
            if (numericData(value)) {
                if (label == null) {
                    return List.of();
                }
                pairs.add(new SummaryPair(first, last, cell.getKey(), label, value));
                label = null;
            } else if (label == null) {
                label = value;
                first = cell.getKey();
                last = first;
            } else if (label.equals(value) && cell.getKey() == last + 1) {
                last = cell.getKey(); // 相邻重复标签来自横向合并单元格。
            } else {
                return List.of();
            }
        }
        if (
            label != null ||
            pairs.size() < 2 ||
            pairs.stream().noneMatch(pair -> SUMMARY_METRIC.matcher(pair.label().toUpperCase(Locale.ROOT)).find())
        ) {
            return List.of();
        }
        return List.copyOf(pairs);
    }

    /** 保存横向汇总指标及原始单元格范围，避免套用明细表头。 */
    private void addSummary(String name, int row, int tableIndex, List<SummaryPair> pairs, ParseBudget budget) {
        List<Map<String, Object>> records = new ArrayList<>();
        StringBuilder text = new StringBuilder("工作表：" + name);
        for (SummaryPair pair : pairs) {
            budget.check();
            String labelCell = columnName(pair.labelFirst()) + row;
            String valueCell = columnName(pair.valueColumn()) + row;
            String labelRange =
                pair.labelFirst() == pair.labelLast()
                    ? labelCell
                    : labelCell + ":" + columnName(pair.labelLast()) + row;
            budget.pendingCharacters(
                (long) text.length() + pair.label().length() + pair.value().length() + SUMMARY_TEXT_OVERHEAD_CHARACTERS
            );
            text.append("\n第 ")
                .append(row)
                .append(" 行：")
                .append(labelCell)
                .append("=")
                .append(pair.label())
                .append("；")
                .append(valueCell)
                .append("=")
                .append(pair.value());
            records.add(
                Map.of(
                    "label",
                    pair.label(),
                    "value",
                    pair.value(),
                    "labelCell",
                    labelCell,
                    "valueCell",
                    valueCell,
                    "labelRange",
                    labelRange
                )
            );
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SourceMetadata.KIND, ParsedBlockProtocol.KEY_VALUE_SUMMARY);
        metadata.put(SourceMetadata.SHEET_NAME, name);
        metadata.put(SourceMetadata.TABLE_INDEX, tableIndex);
        metadata.put(SourceMetadata.ROW_START, row);
        metadata.put(SourceMetadata.ROW_END, row);
        metadata.put("summaryPairs", List.copyOf(records));
        metadata.put(SourceMetadata.FORMULA_POLICY, ParsedBlockProtocol.CACHED_VALUES_ONLY);
        budget.add(new ParsedBlock(text.toString(), null, null, name, metadata));
    }

    /** 读取一行的稀疏单元格，保留实际列位置。 */
    private Map<Integer, String> row(
        XMLStreamReader xml,
        int row,
        List<String> strings,
        long[] cells,
        ParseBudget budget
    ) throws Exception {
        Map<Integer, String> values = new TreeMap<>();
        int nextColumn = 1;
        while (xml.hasNext()) {
            budget.check();
            xml.next();
            if (end(xml, SHEET, SourceMetadata.ROW)) {
                break;
            }
            if (start(xml, SHEET, "c")) {
                budget.limit(++cells[0], budget.properties().maxCells());
                Cell cell = cell(xml, row, nextColumn, strings, budget);
                if (values.putIfAbsent(cell.column(), cell.value()) != null || cell.column() < nextColumn) {
                    throw ParseBudget.invalid();
                }
                nextColumn = cell.column() + 1;
            }
        }
        return values;
    }

    /** 读取单元格文本、共享字符串或公式缓存值。 */
    private Cell cell(XMLStreamReader xml, int row, int nextColumn, List<String> strings, ParseBudget budget)
        throws Exception {
        String reference = xml.getAttributeValue(null, "r");
        int column = nextColumn;
        if (reference != null) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("([A-Z]+)([1-9][0-9]*)").matcher(reference);
            if (!match.matches() || Integer.parseInt(match.group(2)) != row) {
                throw ParseBudget.invalid();
            }
            column = 0;
            for (char c : match.group(1).toCharArray()) {
                column = Math.addExact(Math.multiplyExact(column, COLUMN_NAME_RADIX), c - 'A' + 1);
            }
        }
        budget.limit(column, budget.properties().maxColumns());
        String type = xml.getAttributeValue(null, "t");
        String value = "";
        String formula = "";
        StringBuilder inline = new StringBuilder();
        boolean phonetic = false;
        while (xml.hasNext()) {
            budget.check();
            xml.next();
            if (end(xml, SHEET, "c")) {
                break;
            }
            if (start(xml, SHEET, "rPh")) {
                phonetic = true;
            }
            if (end(xml, SHEET, "rPh")) {
                phonetic = false;
            }
            if (start(xml, SHEET, "v")) {
                value = OfficeArchive.text(xml, budget);
            }
            if (start(xml, SHEET, "f")) {
                formula = OfficeArchive.text(xml, budget);
            }
            if (start(xml, SHEET, "t") && !phonetic) {
                inline.append(OfficeArchive.text(xml, budget));
            }
        }
        if ("s".equals(type)) {
            int index = Integer.parseInt(value);
            if (index < 0 || index >= strings.size()) {
                throw ParseBudget.invalid();
            }
            value = strings.get(index);
        } else if ("inlineStr".equals(type)) {
            value = inline.toString();
        } else if ("b".equals(type)) {
            if ("1".equals(value)) {
                value = ParsedBlockProtocol.TRUE;
            } else if ("0".equals(value)) {
                value = ParsedBlockProtocol.FALSE;
            }
        }
        if (value.isEmpty() && !formula.isEmpty()) {
            value = "[公式无缓存] =" + formula;
        }
        return new Cell(column, value);
    }

    /** 保存带表头和原始行范围的一组工作表记录。 */
    private void addGroup(
        String name,
        Map<Integer, String> headers,
        int headerRow,
        int first,
        int last,
        List<String> rows,
        List<Map<String, Object>> records,
        String kind,
        int tableIndex,
        ParseBudget budget
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, String> columns = new LinkedHashMap<>();
        headers.forEach((column, header) -> columns.put(columnName(column), header));
        metadata.put(SourceMetadata.KIND, kind);
        metadata.put(SourceMetadata.SHEET_NAME, name);
        metadata.put("header", columns);
        metadata.put("tableRows", List.copyOf(records));
        metadata.put(SourceMetadata.TABLE_INDEX, tableIndex);
        metadata.put("headerRow", headerRow);
        metadata.put(SourceMetadata.ROW_START, first);
        metadata.put(SourceMetadata.ROW_END, last);
        metadata.put(SourceMetadata.FORMULA_POLICY, ParsedBlockProtocol.CACHED_VALUES_ONLY);
        budget.add(new ParsedBlock("工作表：" + name + "\n" + String.join("\n", rows), null, null, name, metadata));
    }

    /** 构造保留原始列坐标的单行记录。 */
    private Map<String, Object> record(int row, Map<Integer, String> values, List<Merge> merges) {
        Map<String, String> columns = new LinkedHashMap<>();
        values.forEach((column, value) -> columns.put(columnName(column), value));
        boolean title =
            !values.isEmpty() &&
            merges
                .stream()
                .anyMatch(
                    merge ->
                        merge.firstRow() == row &&
                        merge.firstColumn() == 1 &&
                        merge.lastColumn() >= Collections.max(values.keySet()) &&
                        merge.lastColumn() > 1
                );
        return Map.of(SourceMetadata.ROW, row, SourceMetadata.CELLS, columns, SourceMetadata.MERGED_TITLE_ROW, title);
    }

    /** 读取工作表合并范围并校验行列资源限制。 */
    private List<Merge> merges(OfficeArchive archive, String part, ParseBudget budget) throws Exception {
        List<Merge> merges = new ArrayList<>();
        XMLStreamReader xml = archive.xml(part);
        long cells = 0;
        try {
            while (xml.hasNext()) {
                budget.check();
                xml.next();
                if (!start(xml, SHEET, "mergeCell")) {
                    continue;
                }
                String reference = xml.getAttributeValue(null, "ref");
                java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                    "([A-Z]+)([1-9][0-9]*):([A-Z]+)([1-9][0-9]*)"
                ).matcher(reference == null ? "" : reference);
                if (!match.matches()) {
                    throw ParseBudget.invalid();
                }
                int firstColumn = columnIndex(match.group(1));
                int lastColumn = columnIndex(match.group(RANGE_LAST_COLUMN_GROUP));
                int firstRow = Integer.parseInt(match.group(2));
                int lastRow = Integer.parseInt(match.group(RANGE_LAST_ROW_GROUP));
                if (lastRow < firstRow || lastColumn < firstColumn) {
                    throw ParseBudget.invalid();
                }
                budget.limit(lastRow, budget.properties().maxRowsPerSheet());
                budget.limit(lastColumn, budget.properties().maxColumns());
                cells += (long) (lastRow - firstRow + 1) * (lastColumn - firstColumn + 1);
                budget.limit(cells, budget.properties().maxCells());
                merges.add(new Merge(firstRow, lastRow, firstColumn, lastColumn));
            }
        } finally {
            xml.close();
        }
        return merges;
    }

    /** 将 Excel 列字母转换为从 1 开始的列号。 */
    private int columnIndex(String name) {
        int value = 0;
        for (char character : name.toCharArray()) {
            value = Math.addExact(Math.multiplyExact(value, COLUMN_NAME_RADIX), character - 'A' + 1);
        }
        return value;
    }

    /** 将从 1 开始的列号转换为 Excel 列字母。 */
    private String columnName(int column) {
        StringBuilder name = new StringBuilder();
        while (column > 0) {
            column--;
            name.insert(0, (char) ('A' + (column % COLUMN_NAME_RADIX)));
            column /= COLUMN_NAME_RADIX;
        }
        return name.toString();
    }

    private static final class PathNormalizer {

        /** 规范化压缩包内部路径，处理相对路径段。 */
        static String normalize(String path) {
            Deque<String> parts = new ArrayDeque<>();
            for (String part : path.split("/")) {
                if (part.equals("..")) {
                    if (parts.isEmpty()) {
                        throw ParseBudget.invalid();
                    }
                    parts.removeLast();
                } else if (!part.equals(".") && !part.isEmpty()) {
                    parts.addLast(part);
                }
            }
            return String.join("/", parts);
        }
    }
}
