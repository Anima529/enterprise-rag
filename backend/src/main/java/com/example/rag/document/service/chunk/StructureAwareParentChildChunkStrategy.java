package com.example.rag.document.service.chunk;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.chunk.ChunkDraft.SourceSpan;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.domain.index.IndexFailureCode;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.support.parser.SourceMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class StructureAwareParentChildChunkStrategy implements ChunkStrategy {

    public static final String VERSION = "structure-parent-child-v3-tables";
    private static final Pattern ROW = Pattern.compile("(?m)^第 (\\d+) 行：");
    private final ChunkTokenizer tokenizer;

    /** 配置用于控制父子 Chunk 大小的 Token 计数器。 */
    public StructureAwareParentChildChunkStrategy(ChunkTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    private record Slice(int blockIndex, ParsedBlock block, int start, int end) {
        /** 返回切片在原解析块中的实际文本范围。 */
        String text() {
            return block.text().substring(start, end);
        }
    }

    private record Range(int start, int end) {}

    private static final class Budget {

        final long deadline;
        final int maxChunks;

        /** 从切分配置建立截止时间与最大产物数量。 */
        Budget(ChunkProperties config) {
            deadline = System.nanoTime() + config.timeout().toNanos();
            maxChunks = config.maxChunks();
        }

        /** 检查切分截止时间和线程中断，避免失去租约后继续工作。 */
        void check() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                throw new IndexFailure(IndexFailureCode.DOC_504_CHUNK_TIMEOUT, "分块超时，请调整分块预算后重试", false);
            }
        }

        /** 检查产物资源上限后追加 Chunk 草稿。 */
        void add(List<ChunkDraft> output, ChunkDraft draft) {
            check();
            if (output.size() >= maxChunks) {
                throw new IndexFailure(IndexFailureCode.DOC_413_CHUNK_LIMIT, "分块数量超过资源上限", false);
            }
            output.add(draft);
        }
    }

    /** 按结构边界生成父子 Chunk，保留 Token 限制与来源对应关系。 */
    public List<ChunkDraft> split(ParsedDocument document, ChunkProperties config) {
        Budget budget = new Budget(config);
        List<ChunkDraft> output = new ArrayList<>();
        List<Slice> parent = new ArrayList<>();
        ParsedBlock previous = null;
        for (int index = 0; index < document.blocks().size(); index++) {
            budget.check();
            ParsedBlock block = document.blocks().get(index);
            if (previous != null && boundary(previous, block)) {
                emit(parent, output, config, budget);
                parent.clear();
            }
            for (Range range : ranges(
                block.text(),
                config.parentTokens(),
                0,
                config.boundaryLookbackCharacters(),
                budget
            )) {
                Slice slice = new Slice(index, block, range.start(), range.end());
                if (
                    !parent.isEmpty() &&
                    tokenizer.count(content(parent) + "\n\n" + slice.text()) > config.parentTokens()
                ) {
                    emit(parent, output, config, budget);
                    parent.clear();
                }
                parent.add(slice);
            }
            previous = block;
        }
        emit(parent, output, config, budget);
        if (output.isEmpty()) {
            throw new IndexFailure(IndexFailureCode.DOC_422_EMPTY_CHUNKS, "解析结果没有可分块的正文", false);
        }
        return List.copyOf(output);
    }

    /** 判断相邻解析块是否跨越标题、工作表或表格边界。 */
    private boolean boundary(ParsedBlock previous, ParsedBlock next) {
        return (
            ParsedBlockProtocol.HEADING.equals(next.metadata().get(SourceMetadata.KIND)) ||
            !Objects.equals(previous.chapter(), next.chapter()) ||
            !Objects.equals(previous.section(), next.section()) ||
            !Objects.equals(previous.metadata().get("headingPath"), next.metadata().get("headingPath")) ||
            !Objects.equals(
                previous.metadata().get(SourceMetadata.SHEET_NAME),
                next.metadata().get(SourceMetadata.SHEET_NAME)
            ) ||
            !Objects.equals(
                previous.metadata().get(SourceMetadata.TABLE_INDEX),
                next.metadata().get(SourceMetadata.TABLE_INDEX)
            )
        );
    }

    /** 按切片顺序组合用于切分的原始文本。 */
    private String content(List<Slice> slices) {
        return String.join("\n\n", slices.stream().map(Slice::text).toList());
    }

    /** 生成一个父 Chunk 及其受限重叠的子 Chunk。 */
    private void emit(List<Slice> slices, List<ChunkDraft> output, ChunkProperties config, Budget budget) {
        if (slices.isEmpty()) {
            return;
        }
        String content = content(slices);
        int parentIndex = output.size();
        budget.add(
            output,
            new ChunkDraft(
                ChunkType.PARENT,
                parentIndex,
                null,
                content,
                tokenizer.count(content),
                spans(slices, 0, content.length())
            )
        );
        boolean table = slices
            .stream()
            .allMatch(slice -> ParsedBlockProtocol.TABLE_ROW.equals(slice.block().metadata().get(SourceMetadata.KIND)));
        List<Range> children = table
            ? tableRanges(slices, content, config, budget)
            : ranges(
                  content,
                  config.childTokens(),
                  config.overlapTokens(),
                  config.boundaryLookbackCharacters(),
                  budget
              );
        for (Range range : children) {
            String child = content.substring(range.start(), range.end());
            budget.add(
                output,
                new ChunkDraft(
                    ChunkType.CHILD,
                    output.size(),
                    parentIndex,
                    child,
                    tokenizer.count(child),
                    spans(slices, range.start(), range.end())
                )
            );
        }
    }

    /** 优先按完整表格行生成范围，超长行回退为有来源的片段。 */
    private List<Range> tableRanges(List<Slice> slices, String content, ChunkProperties config, Budget budget) {
        List<Range> output = new ArrayList<>();
        int offset = 0;
        int start = -1;
        int end = 0;
        for (Slice slice : slices) {
            budget.check();
            int rowEnd = offset + slice.text().length();
            if (start >= 0 && tokenizer.count(content.substring(start, rowEnd)) > config.childTokens()) {
                output.add(new Range(start, end));
                start = -1;
            }
            if (tokenizer.count(slice.text()) > config.childTokens()) {
                for (Range range : ranges(
                    slice.text(),
                    config.childTokens(),
                    config.overlapTokens(),
                    config.boundaryLookbackCharacters(),
                    budget
                )) {
                    output.add(new Range(offset + range.start(), offset + range.end()));
                }
            } else {
                if (start < 0) {
                    start = offset;
                }
                end = rowEnd;
            }
            offset = rowEnd + 2;
        }
        if (start >= 0) {
            output.add(new Range(start, end));
        }
        return output;
    }

    /** 在 Token 和标点约束下计算文本范围及局部重叠。 */
    private List<Range> ranges(String text, int size, int overlap, int lookback, Budget budget) {
        List<Range> ranges = new ArrayList<>();
        int start = 0;
        int covered = 0;
        while (start < text.length()) {
            budget.check();
            int end = tokenizer.prefixEnd(text, start, size);
            if (end <= start) {
                throw new IndexFailure(IndexFailureCode.DOC_413_CHUNK_LIMIT, "分块 token 预算不足", false);
            }
            if (end < text.length()) {
                int aligned = PunctuationBoundary.before(text, end, covered, lookback);
                if (aligned > covered && tokenizer.count(text.substring(start, aligned)) <= size) {
                    end = aligned;
                }
            }
            String value = text.substring(start, end);
            if (!value.isBlank()) {
                ranges.add(new Range(start, end));
            }
            if (ranges.size() > budget.maxChunks) {
                throw new IndexFailure(IndexFailureCode.DOC_413_CHUNK_LIMIT, "分块数量超过资源上限", false);
            }
            covered = end;
            if (end == text.length()) {
                break;
            }
            int next = end;
            if (overlap > 0) {
                // 只搜索有限长度的末尾片段，并使用同一分词器计量。
                int candidate = Math.max(start + 1, end - overlap * ChunkTokenizer.MAX_CHARACTERS_PER_TOKEN);
                if (Character.isLowSurrogate(text.charAt(candidate))) {
                    candidate++;
                }
                while (candidate < end && tokenizer.count(text.substring(candidate, end)) > overlap) {
                    candidate += Character.charCount(text.codePointAt(candidate));
                }
                next = candidate;
                // 极短的结构片段不能产生完全重复、没有新增内容的窗口。
                if (tokenizer.count(value) <= overlap) {
                    next = end;
                } else {
                    int aligned = PunctuationBoundary.before(text, next, start, lookback);
                    // 增加重叠后仍需保留足够的 Token 预算以覆盖新内容。
                    if (aligned > start && tokenizer.prefixEnd(text, aligned, size) > end) {
                        next = aligned;
                    }
                }
            }
            start = next;
        }
        return ranges;
    }

    /** 将切分范围映射回原解析块的字符与位置范围。 */
    private List<SourceSpan> spans(List<Slice> slices, int start, int end) {
        List<SourceSpan> spans = new ArrayList<>();
        int offset = 0;
        for (Slice slice : slices) {
            int length = slice.end() - slice.start();
            int from = Math.max(start, offset);
            int to = Math.min(end, offset + length);
            if (from < to) {
                int charStart = slice.start() + from - offset;
                int charEnd = slice.start() + to - offset;
                ParsedBlock block = slice.block();
                spans.add(
                    new SourceSpan(
                        slice.blockIndex(),
                        charStart,
                        charEnd,
                        block.page(),
                        block.chapter(),
                        block.section(),
                        position(block, charStart, charEnd)
                    )
                );
            }
            offset += length + 2;
        }
        return spans;
    }

    /** 根据块类型还原行号、表格行或代码行的来源信息。 */
    private Map<String, Object> position(ParsedBlock block, int start, int end) {
        Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
        if (ParsedBlockProtocol.TABLE_ROW.equals(metadata.get(SourceMetadata.KIND))) {
            metadata.put("tableRowComplete", start == 0 && end == block.text().length());
            return metadata;
        }
        if (metadata.get(SourceMetadata.LINE_START) instanceof Number line) {
            int before = (int) block
                .text()
                .substring(0, start)
                .chars()
                .filter(c -> c == '\n')
                .count();
            int inside = (int) block
                .text()
                .substring(start, end)
                .replaceFirst("\\n$", "")
                .chars()
                .filter(c -> c == '\n')
                .count();
            metadata.put(SourceMetadata.LINE_START, line.intValue() + before);
            metadata.put(SourceMetadata.LINE_END, line.intValue() + before + inside);
        }
        if (ParsedBlockProtocol.ROW_GROUP.equals(metadata.get(SourceMetadata.KIND))) {
            Matcher rows = ROW.matcher(block.text());
            Integer first = null;
            Integer last = null;
            while (rows.find()) {
                int rowEnd = block.text().indexOf('\n', rows.end());
                if (rowEnd < 0) {
                    rowEnd = block.text().length();
                }
                if (rows.start() < end && rowEnd > start) {
                    int row = Integer.parseInt(rows.group(1));
                    if (first == null) {
                        first = row;
                    }
                    last = row;
                }
            }
            if (first != null) {
                metadata.put(SourceMetadata.ROW_START, first);
                metadata.put(SourceMetadata.ROW_END, last);
            } else {
                metadata.remove(SourceMetadata.ROW_START);
                metadata.remove(SourceMetadata.ROW_END);
            }
        }
        return metadata;
    }
}
