package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.service.upload.LegacyWordContent;
import com.example.rag.document.support.parser.SourceMetadata;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.springframework.stereotype.Component;

@Component
public class DocDocumentParser implements DocumentParser {

    private record CellPosition(
        int table,
        int row,
        int cell,
        int columns,
        boolean header,
        boolean verticalContinuation,
        boolean horizontalContinuation
    ) {}

    private record ParagraphText(String text, int endIndex) {}

    /** 声明解析器支持 DOC 格式。 */
    public DocumentFormat format() {
        return DocumentFormat.DOC;
    }

    /** 在解析预算内提取 DOC 内容并保留结构与来源位置。 */
    public ParsedDocument parse(InputStream input, ParseBudget budget) throws Exception {
        byte[] bytes = budget.read(input);
        try (HWPFDocument document = LegacyWordContent.open(bytes, budget.documents(), budget::check)) {
            Range body = document.getRange();
            budget.limit(body.numParagraphs(), budget.properties().maxBlocks());
            Map<Integer, CellPosition> cells = cells(body, budget);
            HeadingPath path = new HeadingPath();
            DocVisibleText visible = new DocVisibleText();
            for (int index = 0; index < body.numParagraphs(); index++) {
                budget.check();
                Paragraph paragraph = body.getParagraph(index);
                int paragraphIndex = index + 1;
                ParagraphText extracted = paragraph(body, index, visible, budget);
                index = extracted.endIndex();
                String text = extracted.text();
                CellPosition cell = cells.get(paragraph.getStartOffset());
                if (text.isBlank() && cell == null) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("paragraphIndex", paragraphIndex);
                if (index + 1 > paragraphIndex) {
                    metadata.put("paragraphEndIndex", index + 1);
                }
                int outline = paragraph.getLvl();
                boolean heading = cell == null && outline >= 0 && outline < HeadingPath.MAX_HEADING_LEVELS;
                if (heading) {
                    path.update(outline + 1, text);
                    metadata.put("headingLevel", outline + 1);
                }
                boolean list = paragraph.getIlfo() != 0;
                if (list) {
                    metadata.put("numberingId", paragraph.getIlfo());
                    metadata.put("listLevel", paragraph.getIlvl());
                }
                if (cell != null) {
                    metadata.put(SourceMetadata.TABLE_INDEX, cell.table());
                    metadata.put(SourceMetadata.ROW_START, cell.row());
                    metadata.put(SourceMetadata.ROW_END, cell.row());
                    metadata.put("cellIndex", cell.cell());
                    metadata.put("tableLevel", Math.max(1, paragraph.getTableLevel()));
                    metadata.put("columnCount", cell.columns());
                    metadata.put("headerCell", cell.header());
                    metadata.put("verticalContinuation", cell.verticalContinuation());
                    metadata.put("horizontalContinuation", cell.horizontalContinuation());
                }
                String blockKind;
                if (cell != null) {
                    blockKind = ParsedBlockProtocol.TABLE_CELL;
                } else if (heading) {
                    blockKind = ParsedBlockProtocol.HEADING;
                } else if (list) {
                    blockKind = ParsedBlockProtocol.LIST;
                } else {
                    blockKind = ParsedBlockProtocol.PARAGRAPH;
                }
                metadata.put(SourceMetadata.KIND, blockKind);
                metadata.put("headingPath", path.titles());
                budget.add(new ParsedBlock(text, null, path.chapter(), path.section(), metadata));
            }
        }
        return new ParsedDocument(format(), budget.blocks(), List.of());
    }

    /** 提取可见段落和标题路径，保留页码及段落来源。 */
    private ParagraphText paragraph(Range body, int index, DocVisibleText visible, ParseBudget budget) {
        StringBuilder text = new StringBuilder();
        while (true) {
            Paragraph paragraph = body.getParagraph(index);
            text.append(visible.paragraph(paragraph, budget));
            budget.pendingCharacters(text.length());
            // 即使没有 Word 段落标记，HWPF 也会在分页字符处结束 Range 段落。
            if (!paragraph.text().endsWith("\u000c") || index + 1 == body.numParagraphs()) {
                break;
            }
            Paragraph next = body.getParagraph(index + 1);
            if (paragraph.isInTable() != next.isInTable() || paragraph.getTableLevel() != next.getTableLevel()) {
                break;
            }
            text.append('\n');
            index++;
        }
        return new ParagraphText(text.toString(), index);
    }

    /** 提取 DOC 表格单元格，保留空列和原始坐标。 */
    private Map<Integer, CellPosition> cells(Range body, ParseBudget budget) {
        Map<Integer, CellPosition> positions = new HashMap<>();
        int tableIndex = 0;
        for (int index = 0; index < body.numParagraphs(); index++) {
            budget.check();
            Paragraph paragraph = body.getParagraph(index);
            if (!paragraph.isInTable()) {
                continue;
            }
            // Word 97 的平面表格层级可能为 0，POI TableIterator 会跳过此类表格。
            Table table = body.getTable(paragraph);
            tableIndex++;
            for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                budget.check();
                TableRow row = table.getRow(rowIndex);
                for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                    budget.check();
                    TableCell cell = row.getCell(cellIndex);
                    for (int paragraphIndex = 0; paragraphIndex < cell.numParagraphs(); paragraphIndex++) {
                        budget.check();
                        positions.put(
                            cell.getParagraph(paragraphIndex).getStartOffset(),
                            new CellPosition(
                                tableIndex,
                                rowIndex + 1,
                                cellIndex + 1,
                                row.numCells(),
                                row.isTableHeader(),
                                cell.isVerticallyMerged() && !cell.isFirstVerticallyMerged(),
                                cell.isMerged() && !cell.isFirstMerged()
                            )
                        );
                        budget.limit(positions.size(), budget.properties().maxBlocks());
                    }
                }
            }
            index += table.numParagraphs() - 1;
        }
        return positions;
    }
}
