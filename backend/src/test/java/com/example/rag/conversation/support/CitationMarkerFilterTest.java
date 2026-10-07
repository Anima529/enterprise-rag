package com.example.rag.conversation.support;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationMarkerFilterTest {

    /** 按每个字符拆分的合法和无效引用仍遵守同一白名单。 */
    @Test
    void shouldValidateMarkersAcrossEveryPossibleDeltaBoundary() {
        String input = "依据[C1]和[C2]，错误[C99]、[C0]、[C01]。";
        for (int split = 0; split <= input.length(); split++) {
            CitationMarkerFilter filter = new CitationMarkerFilter(Set.of("C1", "C2"));
            String output =
                filter.append(input.substring(0, split)) + filter.append(input.substring(split)) + filter.finish();
            assertThat(output).isEqualTo("依据[C1]和[C2]，错误[引用无效]、[引用无效]、[引用无效]。");
        }
    }

    /** 普通 Markdown、中文和 Emoji 不因引用过滤而改变。 */
    @Test
    void shouldKeepOrdinaryMarkdownAndUnicodeIntact() {
        CitationMarkerFilter filter = new CitationMarkerFilter(Set.of("C1"));
        assertThat(filter.append("[链接](https://example.com) [] 😀 [Cost] [C1]") + filter.finish()).isEqualTo(
            "[链接](https://example.com) [] 😀 [Cost] [C1]"
        );
    }

    /** 流末尾不完整编号不能被误认为已经验证的合法来源。 */
    @Test
    void shouldRejectIncompleteMarkersAtTerminalBoundary() {
        CitationMarkerFilter filter = new CitationMarkerFilter(Set.of("C1"));
        assertThat(filter.append("回答[C") + filter.finish()).isEqualTo("回答[引用无效]");
        assertThat(filter.finish()).isEmpty();
    }
}
