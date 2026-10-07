package com.example.rag.document.service.parser;

import static com.example.rag.document.service.parser.OfficeArchive.*;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.support.parser.SourceMetadata;
import java.io.InputStream;
import java.util.*;
import java.util.regex.*;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

@Component
public class DocxDocumentParser implements DocumentParser {

    private record Style(Integer level, String basedOn) {}

    private static final class TablePosition {

        final int index;
        int row;
        int column;
        int nextColumn = 1;
        int span = 1;
        int columns;
        boolean header;
        boolean verticalContinuation;

        /** 登记当前 Word 表格序号以追踪原始位置。 */
        TablePosition(int index) {
            this.index = index;
        }
    }

    private static final Pattern HEADING = Pattern.compile("(?i)(?:heading|标题)\\s*([1-9])");

    /** 声明解析器支持 DOCX 格式。 */
    public DocumentFormat format() {
        return DocumentFormat.DOCX;
    }

    /** 在解析预算内提取 DOCX 内容并保留结构与来源位置。 */
    public ParsedDocument parse(InputStream input, ParseBudget budget) throws Exception {
        try (OfficeArchive archive = new OfficeArchive(format(), input, budget)) {
            Map<String, Style> styles = styles(archive, budget);
            HeadingPath path = new HeadingPath();
            XMLStreamReader xml = archive.xml("word/document.xml");
            int paragraph = 0;
            int table = 0;
            Deque<TablePosition> tables = new ArrayDeque<>();
            try {
                while (xml.hasNext()) {
                    budget.check();
                    xml.next();
                    if (start(xml, WORD, "tbl")) {
                        tables.push(new TablePosition(++table));
                    }
                    if (end(xml, WORD, "tbl")) {
                        tables.pop();
                    }
                    TablePosition position = tables.peek();
                    if (position != null) {
                        if (start(xml, WORD, "gridCol")) {
                            position.columns++;
                        }
                        if (start(xml, WORD, "tr")) {
                            position.row++;
                            position.nextColumn = 1;
                            position.header = false;
                        }
                        if (start(xml, WORD, "gridBefore")) {
                            position.nextColumn += Integer.parseInt(xml.getAttributeValue(WORD, "val"));
                        }
                        if (start(xml, WORD, "tblHeader")) {
                            position.header = !"0".equals(xml.getAttributeValue(WORD, "val"));
                        }
                        if (start(xml, WORD, "tc")) {
                            position.column = position.nextColumn;
                            position.span = 1;
                            position.verticalContinuation = false;
                        }
                        if (start(xml, WORD, "gridSpan")) {
                            position.span = Integer.parseInt(xml.getAttributeValue(WORD, "val"));
                            budget.limit(position.span, budget.properties().maxColumns());
                            if (position.span < 1) {
                                throw ParseBudget.invalid();
                            }
                        }
                        if (start(xml, WORD, "vMerge")) {
                            position.verticalContinuation = !"restart".equals(xml.getAttributeValue(WORD, "val"));
                        }
                        if (end(xml, WORD, "tc")) {
                            position.nextColumn = position.column + position.span;
                        }
                    }
                    if (start(xml, WORD, "p")) {
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("paragraphIndex", ++paragraph);
                        if (position != null) {
                            metadata.put(SourceMetadata.TABLE_INDEX, position.index);
                            metadata.put(SourceMetadata.ROW_START, position.row);
                            metadata.put(SourceMetadata.ROW_END, position.row);
                            metadata.put("cellIndex", position.column);
                            metadata.put("columnSpan", position.span);
                            metadata.put("columnCount", position.columns);
                            metadata.put("headerCell", position.header);
                            metadata.put("verticalContinuation", position.verticalContinuation);
                            metadata.put("tableLevel", tables.size());
                        }
                        paragraph(xml, styles, path, metadata, budget);
                    }
                }
            } finally {
                xml.close();
            }
        }
        return new ParsedDocument(format(), budget.blocks(), List.of());
    }

    /** 读取段落可见文本与标题层级，保留来源顺序。 */
    private void paragraph(
        XMLStreamReader xml,
        Map<String, Style> styles,
        HeadingPath path,
        Map<String, Object> metadata,
        ParseBudget budget
    ) throws Exception {
        StringBuilder text = new StringBuilder();
        Integer level = null;
        boolean explicitOutline = false;
        String style = null;
        boolean list = false;
        int depth = 1;
        int deletedDepth = 0;
        while (xml.hasNext() && depth > 0) {
            budget.check();
            int event = xml.next();
            if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                depth++;
            }
            if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
            if (start(xml, WORD, "del")) {
                deletedDepth = depth;
            }
            if (end(xml, WORD, "del")) {
                deletedDepth = 0;
            }
            if (deletedDepth > 0) {
                continue;
            }
            if (start(xml, WORD, "pStyle")) {
                style = xml.getAttributeValue(WORD, "val");
            }
            if (start(xml, WORD, "outlineLvl")) {
                level = outline(xml.getAttributeValue(WORD, "val"));
                explicitOutline = true;
            }
            if (start(xml, WORD, "numPr")) {
                list = true;
            }
            if (start(xml, WORD, "numId")) {
                metadata.put("numberingId", xml.getAttributeValue(WORD, "val"));
            }
            if (start(xml, WORD, "ilvl")) {
                metadata.put("listLevel", Integer.parseInt(xml.getAttributeValue(WORD, "val")));
            }
            if (start(xml, WORD, "t")) {
                text.append(OfficeArchive.text(xml, budget));
                depth--;
            }
            if (start(xml, WORD, "tab")) {
                text.append('\t');
            }
            if (start(xml, WORD, "br") || start(xml, WORD, "cr")) {
                text.append('\n');
            }
            budget.limit(text.length(), budget.documents().maxTextCharacters());
        }
        if (!explicitOutline) {
            level = resolve(style, styles, new HashSet<>());
        }
        String value = text.toString().strip();
        if (metadata.containsKey(SourceMetadata.TABLE_INDEX)) {
            level = null;
        }
        if (level != null && !value.isBlank()) {
            path.update(level, value);
            metadata.put("headingLevel", level);
        }
        String blockKind;
        if (metadata.containsKey(SourceMetadata.TABLE_INDEX)) {
            blockKind = ParsedBlockProtocol.TABLE_CELL;
        } else if (level != null) {
            blockKind = ParsedBlockProtocol.HEADING;
        } else if (list) {
            blockKind = ParsedBlockProtocol.LIST;
        } else {
            blockKind = ParsedBlockProtocol.PARAGRAPH;
        }
        metadata.put(SourceMetadata.KIND, blockKind);
        metadata.put("headingPath", path.titles());
        budget.add(new ParsedBlock(value, null, path.chapter(), path.section(), metadata));
    }

    /** 读取段落样式及继承关系，用于识别自定义标题。 */
    private Map<String, Style> styles(OfficeArchive archive, ParseBudget budget) throws Exception {
        Map<String, Style> styles = new HashMap<>();
        if (!archive.has("word/styles.xml")) {
            return styles;
        }
        XMLStreamReader xml = archive.xml("word/styles.xml");
        String id = null;
        String basedOn = null;
        Integer level = null;
        try {
            while (xml.hasNext()) {
                budget.check();
                xml.next();
                if (start(xml, WORD, "style")) {
                    id = xml.getAttributeValue(WORD, "styleId");
                    basedOn = null;
                    level = namedLevel(id);
                }
                if (start(xml, WORD, "name") && level == null) {
                    level = namedLevel(xml.getAttributeValue(WORD, "val"));
                }
                if (start(xml, WORD, "outlineLvl")) {
                    level = outline(xml.getAttributeValue(WORD, "val"));
                }
                if (start(xml, WORD, "basedOn")) {
                    basedOn = xml.getAttributeValue(WORD, "val");
                }
                if (end(xml, WORD, "style") && id != null) {
                    budget.limit(styles.size() + 1L, budget.properties().maxBlocks());
                    styles.put(id, new Style(level, basedOn));
                    id = null;
                }
            }
        } finally {
            xml.close();
        }
        return styles;
    }

    /** 沿样式继承链解析标题层级并避免循环引用。 */
    private Integer resolve(String id, Map<String, Style> styles, Set<String> visited) {
        while (id != null && visited.add(id)) {
            Style style = styles.get(id);
            if (style == null) {
                return namedLevel(id);
            }
            if (style.level() != null) {
                return style.level();
            }
            id = style.basedOn();
        }
        return null;
    }

    /** 从样式名称识别标准标题级别。 */
    private Integer namedLevel(String name) {
        Matcher matcher = HEADING.matcher(name == null ? "" : name);
        return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** 将大纲级别转换为支持的标题层级。 */
    private Integer outline(String value) {
        int level = Integer.parseInt(value);
        return level >= 0 && level < HeadingPath.MAX_HEADING_LEVELS ? level + 1 : null;
    }
}
