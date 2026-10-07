package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.support.parser.SourceMetadata;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 按块保留来源内容，不调用 HTML 渲染器、URL 解析器或可执行代码。 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final Pattern ATX = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|[ \\t]*)$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])\\s+.*$");

    /** 声明解析器支持 Markdown 格式。 */
    public DocumentFormat format() {
        return DocumentFormat.MD;
    }

    /** 在解析预算内提取 Markdown 内容并保留结构与来源位置。 */
    public ParsedDocument parse(InputStream input, ParseBudget budget) throws Exception {
        String[] lines = TextSource.lines(input, budget);
        HeadingPath path = new HeadingPath();
        for (int start = 0; start < lines.length; ) {
            budget.check();
            if (lines[start].isBlank()) {
                start++;
                continue;
            }
            int end = start + 1;
            Map<String, Object> metadata = new LinkedHashMap<>();
            Matcher atx = ATX.matcher(lines[start]);
            Matcher fence = FENCE.matcher(lines[start]);
            if (atx.matches() || isSetext(lines, start)) {
                int level = headingLevel(atx, lines, start);
                String title = headingTitle(atx, lines, start);
                path.update(level, title);
                metadata.put(SourceMetadata.KIND, ParsedBlockProtocol.HEADING);
                metadata.put("headingLevel", level);
                if (!atx.matches()) {
                    end++;
                }
            } else if (fence.matches()) {
                String marker = fence.group(1);
                Pattern close = Pattern.compile(
                    "^ {0,3}" + Pattern.quote(marker.substring(0, 1)) + "{" + marker.length() + ",}\\s*$"
                );
                while (end < lines.length && !close.matcher(lines[end]).matches()) {
                    budget.check();
                    end++;
                }
                boolean closed = end < lines.length;
                if (closed) {
                    end++;
                }
                metadata.put(SourceMetadata.KIND, ParsedBlockProtocol.CODE);
                metadata.put("language", fence.group(2).strip());
                metadata.put("closed", closed);
            } else {
                String kind;
                if (LIST.matcher(lines[start]).matches()) {
                    kind = ParsedBlockProtocol.LIST;
                } else if (isTable(lines, start)) {
                    kind = ParsedBlockProtocol.TABLE;
                } else {
                    kind = ParsedBlockProtocol.PARAGRAPH;
                }
                metadata.put(SourceMetadata.KIND, kind);
                if (kind.equals(ParsedBlockProtocol.TABLE)) {
                    end++;
                }
                while (
                    end < lines.length &&
                    !lines[end].isBlank() &&
                    !ATX.matcher(lines[end]).matches() &&
                    !FENCE.matcher(lines[end]).matches() &&
                    !isSetext(lines, end)
                ) {
                    budget.check();
                    if (kind.equals(ParsedBlockProtocol.TABLE) && !lines[end].contains("|")) {
                        break;
                    }
                    if (
                        kind.equals(ParsedBlockProtocol.PARAGRAPH) &&
                        (LIST.matcher(lines[end]).matches() || isTable(lines, end))
                    ) {
                        break;
                    }
                    end++;
                }
            }
            metadata.put(SourceMetadata.LINE_START, start + 1);
            metadata.put(SourceMetadata.LINE_END, end);
            metadata.put("headingPath", path.titles());
            budget.add(
                new ParsedBlock(
                    String.join("\n", Arrays.copyOfRange(lines, start, end)),
                    null,
                    path.chapter(),
                    path.section(),
                    metadata
                )
            );
            start = end;
        }
        return new ParsedDocument(format(), budget.blocks(), List.of());
    }

    /** 识别受支持的 Markdown 标题级别。 */
    private int headingLevel(Matcher atx, String[] lines, int start) {
        if (atx.matches()) {
            return atx.group(1).length();
        }
        return lines[start + 1].strip().charAt(0) == '=' ? 1 : 2;
    }

    /** 提取标题正文并保留字面量井号规则。 */
    private String headingTitle(Matcher atx, String[] lines, int start) {
        if (atx.matches()) {
            if (atx.group(2) == null) {
                return "";
            }
            return atx.group(2).strip().replaceFirst("[ \\t]+#+$", "");
        }
        return lines[start].strip();
    }

    /** 判断行是否为 Setext 标题下划线。 */
    private boolean isSetext(String[] lines, int start) {
        return (
            start + 1 < lines.length &&
            !lines[start].isBlank() &&
            !FENCE.matcher(lines[start]).matches() &&
            !LIST.matcher(lines[start]).matches() &&
            !lines[start].matches("(?: {4}|\\t).*") &&
            lines[start + 1].matches(" {0,3}(?:=+|-+)\\s*")
        );
    }

    /** 判断内容是否符合 Markdown 表格行结构。 */
    private boolean isTable(String[] lines, int start) {
        return (
            start + 1 < lines.length &&
            lines[start].contains("|") &&
            lines[start + 1].matches("\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*")
        );
    }
}
