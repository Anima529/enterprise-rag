package com.example.rag.rag.chat.service;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.conversation.service.CitationPreviewBuilder;
import com.example.rag.document.dto.preview.SourcePreviewModels.*;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.prompt.support.PromptFixtures;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {

    private final ChunkTokenizer tokenizer = new ChunkTokenizer();
    private ChatProperties config = new ChatProperties(
        "qwen-plus",
        "https://example.invalid",
        "",
        "",
        8,
        .2,
        512,
        32,
        100,
        128,
        1000,
        200,
        2,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofMillis(200)
    );

    /** 构造带父块内容、相似度和版本来源的检索候选。 */
    private Hit hit(long child, long parent, double distance, String content) {
        return new Hit(
            child,
            parent,
            1,
            2,
            3,
            4,
            "child",
            "{\"sources\":[{\"metadata\":{\"lineStart\":2,\"lineEnd\":5}}]}",
            distance,
            content,
            "制度.txt",
            null,
            "制度",
            "报销"
        );
    }

    /** 验证父块去重、弱证据过滤及版本来源保留。 */
    @Test
    void shouldDeduplicateParentsFilterWeakEvidenceAndRetainVersionedSources() throws Exception {
        ContextBuilder.Context result = new ContextBuilder(
            config,
            tokenizer,
            new ObjectMapper(),
            new CitationPreviewBuilder(
                new ObjectMapper(),
                new com.example.rag.document.service.preview.SourcePreviewBuilder()
            ),
            PromptFixtures.catalog()
        ).build("报销？", List.of(hit(10, 1, 0, "五日审批"), hit(11, 1, .1, "五日审批"), hit(12, 2, .99, "不相关")));
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().getFirst().documentVersion()).isEqualTo(4);
        assertThat(result.citations().getFirst().lineStart()).isEqualTo(2);
        assertThat(result.citations().getFirst().lineEnd()).isEqualTo(5);
        assertThat(result.request().messages().getFirst().content()).doesNotContain("不相关");
    }

    /** 相邻 Parent 连续排列并共享文档标题，但保持各自稳定引用与实际正文快照。 */
    @Test
    void shouldMergeAdjacentParentsWithoutLosingCitationBindings() throws Exception {
        ContextBuilder builder = new ContextBuilder(
            config,
            tokenizer,
            new ObjectMapper(),
            new CitationPreviewBuilder(
                new ObjectMapper(),
                new com.example.rag.document.service.preview.SourcePreviewBuilder()
            ),
            PromptFixtures.catalog()
        );
        Hit first = withIndex(hit(1, 10, 0, "前一段内容"), 1);
        Hit second = withIndex(hit(2, 11, 0, "后一段内容"), 2);
        ContextBuilder.Context context = builder.build("问题", List.of(second, first));
        assertThat(context.citations())
            .extracting(com.example.rag.conversation.dto.ConversationModels.Citation::chunkId)
            .containsExactly(10L, 11L);
        assertThat(context.request().messages().getFirst().content()).contains("[C1] 文档：", "[C2]（同文档接续片段）");
        assertThat(context.evidence().get("C1").content()).isEqualTo("前一段内容");
        assertThat(context.evidence().get("C2").content()).isEqualTo("后一段内容");
    }

    /** 构造真实 SQL 附加的父块物理序号，避免依靠 Chunk ID 猜测邻接。 */
    private Hit withIndex(Hit hit, int index) {
        return new Hit(
            hit.chunkId(),
            hit.parentChunkId(),
            hit.knowledgeBaseId(),
            hit.departmentId(),
            hit.documentId(),
            hit.documentVersion(),
            hit.content(),
            "{\"sources\":[],\"parentChunkIndex\":" + index + "}",
            hit.distance(),
            hit.parentContent(),
            hit.documentName(),
            hit.pageNumber(),
            hit.chapter(),
            hit.section()
        );
    }

    /** 验证上下文 Token 预算及无足够证据时返回空结果。 */
    @Test
    void shouldEnforceContextTokenBudgetAndConservativelyReturnEmptyEvidence() throws Exception {
        ContextBuilder builder = new ContextBuilder(
            config,
            tokenizer,
            new ObjectMapper(),
            new CitationPreviewBuilder(
                new ObjectMapper(),
                new com.example.rag.document.service.preview.SourcePreviewBuilder()
            ),
            PromptFixtures.catalog()
        );
        ContextBuilder.Context result = builder.build("问题", List.of(hit(10, 1, 0, "中文制度审批。".repeat(300))));
        assertThat(
            tokenizer.count(result.request().systemPrompt()) +
                tokenizer.count(result.request().messages().getFirst().content())
        ).isLessThanOrEqualTo(config.contextTokens());
        assertThat(result.citations()).hasSize(1);
        assertThat(builder.build("问题", List.of()).citations()).isEmpty();
    }
}
