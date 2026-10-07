package com.example.rag.rag.chat.support;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;

class EvidenceMetadataTest {

    /** Token 裁剪后的来源字符和行号只覆盖入模正文，不包含后续完整表格行。 */
    @Test
    void shouldClipSourceSpansLinesAndTableCompletenessToActualEvidence() {
        EvidenceMetadata metadata = new EvidenceMetadata(new ObjectMapper());
        JsonNode clipped = metadata.clip(
            """
            {"sources":[
              {"blockIndex":0,"charStart":5,"charEnd":15,"metadata":{"lineStart":3,"lineEnd":5,"tableRowComplete":true}},
              {"blockIndex":1,"charStart":0,"charEnd":9,"metadata":{"lineStart":6,"lineEnd":7}}
            ]}
            """,
            "甲\n乙"
        );
        assertThat(clipped.path("sources")).hasSize(1);
        assertThat(clipped.path("sources").get(0).path("charEnd").asInt()).isEqualTo(8);
        assertThat(clipped.path("sources").get(0).path("metadata").path("lineEnd").asInt()).isEqualTo(4);
        assertThat(clipped.path("sources").get(0).path("metadata").path("tableRowComplete").asBoolean()).isFalse();
    }
}
