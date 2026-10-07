package com.example.rag.document.service.parser;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.config.parser.ParserProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.support.DocumentFixtures;
import com.example.rag.document.support.LegacyDocFixtures;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocDocumentParserTest {

    private final DocumentParsingService service = DocumentParsingServiceTest.service(
        DocumentParsingServiceTest.properties(),
        DocumentFixtures.properties(10485760)
    );

    /** 使用用例指定格式和预算执行解析。 */
    private ParsedDocument parse(byte[] bytes) {
        return service.parse(DocumentFormat.DOC, new ByteArrayInputStream(bytes));
    }

    /** 验证中文标题路径及跨显式分页的单段落保持完整。 */
    @Test
    void shouldKeepChineseHeadingPathsAndOneParagraphAcrossExplicitPageBreak() throws Exception {
        ParsedDocument result = parse(LegacyDocFixtures.sample());
        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks().getFirst().metadata())
            .containsEntry("kind", "HEADING")
            .containsEntry("headingLevel", 1);
        ParsedBlock body = result.blocks().getLast();
        assertThat(body.text()).isEqualTo("员工须在五个工作日内提交凭证。\n跨页仍保留在同一段落。");
        assertThat(body.page()).isNull();
        assertThat(body.chapter()).isEqualTo("中文公司制度");
        assertThat(body.section()).isEqualTo("中文公司制度 / 差旅报销");
        assertThat(body.metadata())
            .containsEntry("paragraphIndex", 3)
            .containsEntry("paragraphEndIndex", 4)
            .containsEntry("headingPath", List.of("中文公司制度", "差旅报销"));
    }

    /** 验证表格单元格坐标保留且排除 Word 控制标记。 */
    @Test
    void shouldKeepTableCellCoordinatesWithoutWordControlMarkers() throws Exception {
        ParsedDocument result = parse(LegacyDocFixtures.read("simple-table.doc"));
        List<ParsedBlock> rows = result
            .blocks()
            .stream()
            .filter(block -> block.metadata().containsKey("tableIndex"))
            .toList();
        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst().metadata())
            .containsEntry("tableIndex", 1)
            .containsEntry("rowStart", 1)
            .containsEntry("kind", "TABLE_ROW");
        assertThat(rows.getFirst().metadata().get("tableCells")).isInstanceOf(Map.class);
        assertThat(rows).allSatisfy(block -> assertThat(block.text()).doesNotContain("\u0007", "\r"));
    }

    /** 验证跨段落字段保留缓存展示文字且不请求外链。 */
    @Test
    void shouldKeepCachedFieldDisplayTextAcrossParagraphsWithoutFetchingExternalLinks() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
            ParsedDocument result = parse(
                LegacyDocFixtures.text("开头\u0013HYPERLINK \"" + url + "\"\r\u0014可见链接\u0015结束")
            );
            assertThat(result.blocks()).extracting(ParsedBlock::text).containsExactly("开头", "可见链接结束");
            assertThat(result.blocks()).allSatisfy(block ->
                assertThat(block.text()).doesNotContain("HYPERLINK", url, "\u0013", "\u0014", "\u0015")
            );
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    /** 验证空、加密、宏及非 Word 二进制内容明确失败。 */
    @Test
    void shouldFailClearlyForEmptyEncryptedMacroAndNonWordBinaryContent() throws Exception {
        assertFailure(LegacyDocFixtures.read("empty.doc"), "DOC_422_EMPTY_TEXT");
        assertFailure(LegacyDocFixtures.encrypted(), "DOC_422_PARSE_FAILED");
        assertFailure(LegacyDocFixtures.read("SimpleMacro.doc"), "DOC_422_PARSE_FAILED");
        assertFailure(LegacyDocFixtures.otherOleDocument(), "DOC_422_PARSE_FAILED");
    }

    /** 验证 DOC 字符和段落资源限制。 */
    @Test
    void shouldEnforceDocCharacterAndParagraphLimits() throws Exception {
        byte[] bytes = LegacyDocFixtures.sample();
        ParserProperties parsers = new ParserProperties(
            Duration.ofSeconds(30),
            2,
            50,
            50000,
            256,
            500000,
            20,
            100000,
            3,
            0.6,
            12,
            4,
            4
        );
        DocumentParsingService blockLimited = DocumentParsingServiceTest.service(
            parsers,
            DocumentFixtures.properties(10485760)
        );
        assertThatThrownBy(() ->
            blockLimited.parse(DocumentFormat.DOC, new ByteArrayInputStream(bytes))
        ).isInstanceOfSatisfying(IndexFailure.class, failure ->
            assertThat(failure.code()).isEqualTo("DOC_413_PARSE_LIMIT")
        );
        DocumentProperties documents = new DocumentProperties(
            10485760,
            3,
            2000,
            104857600,
            20971520,
            200,
            2000,
            60,
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofMinutes(5),
            10
        );
        DocumentParsingService characterLimited = DocumentParsingServiceTest.service(
            DocumentParsingServiceTest.properties(),
            documents
        );
        assertThatThrownBy(() ->
            characterLimited.parse(DocumentFormat.DOC, new ByteArrayInputStream(bytes))
        ).isInstanceOfSatisfying(IndexFailure.class, failure ->
            assertThat(failure.code()).isEqualTo("DOC_413_PARSE_LIMIT")
        );
    }

    /** 断言解析失败使用预期阶段错误码。 */
    private void assertFailure(byte[] bytes, String code) {
        assertThatThrownBy(() -> parse(bytes)).isInstanceOfSatisfying(IndexFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.retryable()).isFalse();
        });
    }
}
