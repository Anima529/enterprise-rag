package com.example.rag.document.service.chunk;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.chunk.ChunkDraft.SourceSpan;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.exception.index.IndexFailure;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructureAwareParentChildChunkStrategyTest {

    private final ChunkTokenizer tokens = new ChunkTokenizer();
    private final ChunkStrategy strategy = new StructureAwareParentChildChunkStrategy(tokens);

    /** 构造用例所需的模型或切分配置。 */
    private ChunkProperties config(int child, int parent, int overlap) {
        return config(child, parent, overlap, 100);
    }

    /** 构造用例所需的模型或切分配置。 */
    private ChunkProperties config(int child, int parent, int overlap, int lookback) {
        return new ChunkProperties(child, parent, overlap, 30000, Duration.ofSeconds(30), 2, lookback);
    }

    /** 构造带结构及来源元数据的解析块。 */
    private ParsedBlock block(String text, String section, Map<String, Object> metadata) {
        return new ParsedBlock(text, 2, "chapter", section, metadata);
    }

    /** 用指定配置执行父子 Chunk 切分并返回草稿。 */
    private List<ChunkDraft> split(List<ParsedBlock> blocks, ChunkProperties config) {
        return strategy.split(new ParsedDocument(DocumentFormat.MD, blocks, List.of()), config);
    }

    /** 验证多语言长段落完整覆盖且父子 Token 上限均满足。 */
    @Test
    void shouldCoverLongMultilingualParagraphsWithoutLosingUnicodeAndRespectBothTokenLimits() {
        String text = "中文制度。English sentence with numbers 123. \uD83D\uDE80 café\n".repeat(180);
        List<ParsedBlock> blocks = List.of(
            block(text, "section", Map.of("kind", "PARAGRAPH", "lineStart", 9, "lineEnd", 188))
        );
        List<ChunkDraft> chunks = split(blocks, config(48, 160, 8));
        assertThat(chunks).hasSizeGreaterThan(10);
        assertThat(
            chunks
                .stream()
                .filter(c -> c.chunkType() == ChunkType.PARENT)
                .flatMap(c -> c.sources().stream())
                .map(s -> text.substring(s.charStart(), s.charEnd()))
                .reduce("", String::concat)
        ).isEqualTo(text);
        assertValid(chunks, blocks, 48, 160);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content()).doesNotContain("\uFFFD"));
        assertThat(split(blocks, config(48, 160, 8))).isEqualTo(chunks);
    }

    /** 验证子块只属于一个父块且重叠限制在该父块内。 */
    @Test
    void shouldKeepEveryChildWithinOneParentAndApplyOnlyBoundedOverlapInsideThatParent() {
        String text = "abcdefghijklmnopqrstuvwxyz0123456789 ".repeat(80);
        List<ChunkDraft> chunks = split(
            List.of(block(text, "same", Map.of("kind", "PARAGRAPH"))),
            config(40, 300, 7, 0)
        );
        for (ChunkDraft parent : chunks
            .stream()
            .filter(c -> c.chunkType() == ChunkType.PARENT)
            .toList()) {
            List<ChunkDraft> children = chunks
                .stream()
                .filter(c -> c.parentIndex() != null && c.parentIndex() == parent.chunkIndex())
                .toList();
            int previousEnd = -1;
            for (ChunkDraft child : children) {
                SourceSpan span = child.sources().getFirst();
                if (previousEnd >= 0) {
                    assertThat(span.charStart()).isLessThan(previousEnd);
                    assertThat(tokens.count(text.substring(span.charStart(), previousEnd))).isBetween(1, 7);
                    assertThat(span.charEnd()).isGreaterThan(previousEnd);
                }
                previousEnd = span.charEnd();
            }
        }
    }

    /** 验证标题出现位置、章节、工作表及表格边界被保留。 */
    @Test
    void shouldRespectHeadingOccurrencesSectionsSheetsAndTableBoundaries() {
        List<ParsedBlock> blocks = List.of(
            block("# repeated", "A", Map.of("kind", "HEADING", "headingPath", List.of("A"))),
            block("first paragraph", "A", Map.of("kind", "PARAGRAPH", "headingPath", List.of("A"))),
            block("# repeated", "A", Map.of("kind", "HEADING", "headingPath", List.of("A"))),
            block("second paragraph", "B", Map.of("kind", "PARAGRAPH")),
            block("sheet one", "sheet", Map.of("kind", "ROW_GROUP", "sheetName", "one")),
            block("sheet two", "sheet", Map.of("kind", "ROW_GROUP", "sheetName", "two")),
            block("table one", "sheet", Map.of("kind", "TABLE", "tableIndex", 1)),
            block("table two", "sheet", Map.of("kind", "TABLE", "tableIndex", 2))
        );
        List<ChunkDraft> chunks = split(blocks, config(80, 160, 10));
        assertThat(chunks.stream().filter(c -> c.chunkType() == ChunkType.PARENT)).hasSize(7);
        assertThat(chunks.getFirst().sources()).extracting(SourceSpan::blockIndex).containsExactly(0, 1);
        assertValid(chunks, blocks, 80, 160);
    }

    /** 验证小型代码与表格完整保留，不可信文字仅作为数据处理。 */
    @Test
    void shouldKeepSmallCodeAndTablesIntactAndPreserveUntrustedTextAsData() {
        String code = "```java\n<script>untrusted</script>\n<|endoftext|>\n```";
        List<ParsedBlock> blocks = List.of(
            block(code, "code", Map.of("kind", "CODE", "language", "java", "lineStart", 10, "lineEnd", 13))
        );
        List<ChunkDraft> chunks = split(blocks, config(80, 160, 10));
        assertThat(chunks)
            .hasSize(2)
            .allSatisfy(chunk -> assertThat(chunk.content()).isEqualTo(code));
        assertThat(chunks.getLast().sources().getFirst().metadata())
            .containsEntry("language", "java")
            .containsEntry("lineStart", 10);
    }

    /** 验证 Excel 切分行仍关联原始稀疏行号与表头。 */
    @Test
    void shouldLocateSplitExcelRowsAgainstOriginalSparseRowNumbersAndRetainHeaders() {
        String text =
            "工作表：费用\n第 2 行：部门 [A]=研发；金额 [C]=" +
            "100 ".repeat(40) +
            "\n第 9 行：部门 [A]=人事；金额 [C]=" +
            "200 ".repeat(40);
        List<ParsedBlock> blocks = List.of(
            block(
                text,
                "费用",
                Map.of(
                    "kind",
                    "ROW_GROUP",
                    "sheetName",
                    "费用",
                    "rowStart",
                    2,
                    "rowEnd",
                    9,
                    "header",
                    Map.of("A", "部门", "C", "金额")
                )
            )
        );
        List<ChunkDraft> chunks = split(blocks, config(32, 120, 5));
        assertValid(chunks, blocks, 32, 120);
        assertThat(
            chunks
                .stream()
                .filter(c -> c.chunkType() == ChunkType.CHILD)
                .flatMap(c -> c.sources().stream())
        ).anySatisfy(source -> assertThat(source.metadata()).containsEntry("rowStart", 9).containsEntry("rowEnd", 9));
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.sources().getFirst().metadata()).containsEntry("header", Map.of("A", "部门", "C", "金额"))
        );
    }

    /** 验证长代码回退为 Token 切分时保留原始行号。 */
    @Test
    void shouldPreserveOriginalLineNumbersWhenLongCodeFallsBackToTokens() {
        String text = "第一行\n" + "中".repeat(200) + "\n最后一行";
        List<ChunkDraft> chunks = split(
            List.of(block(text, "code", Map.of("kind", "CODE", "lineStart", 10, "lineEnd", 12))),
            config(32, 80, 0)
        );
        assertThat(
            chunks
                .stream()
                .filter(c -> c.chunkType() == ChunkType.CHILD)
                .flatMap(c -> c.sources().stream())
        ).anySatisfy(source ->
            assertThat(source.metadata()).containsEntry("lineStart", 11).containsEntry("lineEnd", 11)
        );
        assertThat(chunks.getLast().sources().getLast().metadata()).containsEntry("lineEnd", 12);
    }

    /** 验证无分隔长文本可回退切分并支持零重叠。 */
    @Test
    void shouldHandleTokenFallbackForVeryLongUnbrokenTextAndZeroOverlap() {
        String text = "界".repeat(3000);
        List<ParsedBlock> blocks = List.of(block(text, null, Map.of()));
        List<ChunkDraft> chunks = split(blocks, config(32, 128, 0));
        assertValid(chunks, blocks, 32, 128);
        assertThat(
            chunks
                .stream()
                .filter(c -> c.chunkType() == ChunkType.CHILD)
                .map(ChunkDraft::content)
                .reduce("", String::concat)
        ).isEqualTo(text);
    }

    /** 验证含列表时子块两端和重叠起点仍按边界对齐。 */
    @Test
    void shouldAlignBothChildEndsAndOverlapStartsEvenWhenTheParentContainsAList() {
        String text =
            "First sentence has several useful words. Second sentence provides more context; third clause, final words!\n".repeat(
                20
            );
        List<ParsedBlock> blocks = List.of(
            block("List item", "same", Map.of("kind", "LIST")),
            block(text, "same", Map.of("kind", "PARAGRAPH"))
        );
        List<ChunkDraft> chunks = split(blocks, config(64, 512, 8, 100));
        assertValid(chunks, blocks, 64, 512);
        boolean expandedOverlap = false;
        for (ChunkDraft parent : chunks
            .stream()
            .filter(c -> c.chunkType() == ChunkType.PARENT)
            .toList()) {
            List<ChunkDraft> children = chunks
                .stream()
                .filter(c -> Integer.valueOf(parent.chunkIndex()).equals(c.parentIndex()))
                .toList();
            int previousEnd = 0;
            for (ChunkDraft child : children) {
                String original = parent.content();
                int start = original.indexOf(child.content(), Math.max(0, previousEnd - 200));
                int end = start + child.content().length();
                assertThat(start).isGreaterThanOrEqualTo(0);
                assertThat(end).isGreaterThan(previousEnd);
                if (start > 0) {
                    assertThat(original.charAt(start - 1)).isIn('\n', '.', ';', '!', '?', ',', ':');
                }
                if (end < original.length()) {
                    assertThat(original.charAt(end - 1)).isIn('\n', '.', ';', '!', '?', ',', ':');
                }
                if (previousEnd > start && tokens.count(original.substring(start, previousEnd)) > 8) {
                    expandedOverlap = true;
                }
                previousEnd = end;
            }
        }
        assertThat(expandedOverlap).isTrue();
    }

    /** 验证不超出配置回看范围且不产生仅含重叠内容的子块。 */
    @Test
    void shouldNotSearchBeyondTheConfiguredLookbackOrEmitOverlapOnlyChildren() {
        String text = "prefix;" + "界".repeat(500);
        List<ParsedBlock> blocks = List.of(block(text, "same", Map.of("kind", "PARAGRAPH")));
        assertThat(split(blocks, config(32, 128, 5, 3))).isEqualTo(split(blocks, config(32, 128, 5, 0)));
        String sentences = "中文说明。".repeat(200);
        List<ParsedBlock> repeated = List.of(block(sentences, "same", Map.of("kind", "PARAGRAPH")));
        List<ChunkDraft> chunks = split(repeated, config(32, 128, 31, 1024));
        assertValid(chunks, repeated, 32, 128);
        for (ChunkDraft parent : chunks
            .stream()
            .filter(c -> c.chunkType() == ChunkType.PARENT)
            .toList()) {
            int previousEnd = -1;
            for (ChunkDraft child : chunks
                .stream()
                .filter(c -> Integer.valueOf(parent.chunkIndex()).equals(c.parentIndex()))
                .toList()) {
                int end = child.sources().getLast().charEnd();
                assertThat(end).isGreaterThan(previousEnd);
                previousEnd = end;
            }
        }
    }

    /** 验证空产物、资源超限、超时及非法配置被拒绝。 */
    @Test
    void shouldRejectEmptyOutputResourceOverflowTimeoutAndInvalidConfiguration() {
        assertThatThrownBy(() -> split(List.of(), config(32, 128, 0))).isInstanceOf(IndexFailure.class);
        List<ParsedBlock> blocks = List.of(block("中".repeat(500), null, Map.of()));
        assertThatThrownBy(() -> split(blocks, new ChunkProperties(32, 128, 0, 2, Duration.ofSeconds(30), 2, 100)))
            .isInstanceOf(IndexFailure.class)
            .extracting("code")
            .isEqualTo("DOC_413_CHUNK_LIMIT");
        assertThatThrownBy(() -> split(blocks, new ChunkProperties(32, 128, 0, 30000, Duration.ofNanos(1), 2, 100)))
            .isInstanceOf(IndexFailure.class)
            .extracting("code")
            .isEqualTo("DOC_504_CHUNK_TIMEOUT");
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(config(100, 50, 100))).isNotEmpty();
            assertThat(factory.getValidator().validate(config(32, 128, 0))).isEmpty();
            assertThat(factory.getValidator().validate(config(32, 128, 0, -1))).isNotEmpty();
            assertThat(factory.getValidator().validate(config(32, 128, 0, 1025))).isNotEmpty();
            assertThat(factory.getValidator().validate(config(32, 128, 0, 0))).isEmpty();
        }
    }

    /** 检查父子关系、Token 上限及来源覆盖均满足切分契约。 */
    private void assertValid(List<ChunkDraft> chunks, List<ParsedBlock> blocks, int childLimit, int parentLimit) {
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.tokenCount())
                .isEqualTo(tokens.count(chunk.content()))
                .isBetween(1, chunk.chunkType() == ChunkType.PARENT ? parentLimit : childLimit);
            assertThat(chunk.sources()).isNotEmpty();
            if (chunk.chunkType() == ChunkType.CHILD) {
                ChunkDraft parent = chunks.get(chunk.parentIndex());
                assertThat(parent.chunkType()).isEqualTo(ChunkType.PARENT);
                assertThat(parent.content()).contains(chunk.content());
            }
            for (SourceSpan source : chunk.sources()) {
                String text = blocks.get(source.blockIndex()).text();
                assertThat(source.charStart()).isBetween(0, text.length() - 1);
                assertThat(source.charEnd()).isBetween(source.charStart() + 1, text.length());
                assertThat(Character.isLowSurrogate(text.charAt(source.charStart()))).isFalse();
                assertThat(Character.isHighSurrogate(text.charAt(source.charEnd() - 1))).isFalse();
            }
        }
        for (ChunkType type : ChunkType.values()) {
            for (int index = 0; index < blocks.size(); index++) {
                BitSet coverage = new BitSet(blocks.get(index).text().length());
                for (ChunkDraft chunk : chunks) {
                    if (chunk.chunkType() != type) {
                        continue;
                    }
                    for (SourceSpan source : chunk.sources()) {
                        if (source.blockIndex() == index) {
                            coverage.set(source.charStart(), source.charEnd());
                        }
                    }
                }
                String original = blocks.get(index).text();
                for (int position = 0; position < original.length(); position++) {
                    if (!Character.isWhitespace(original.charAt(position))) {
                        assertThat(coverage.get(position))
                            .as("%s block %d character %d", type, index, position)
                            .isTrue();
                    }
                }
            }
        }
    }
}
