package com.example.rag.document.service.chunk;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PunctuationBoundaryTest {

    /** 验证换行优先于更近的句末和分句标点。 */
    @Test
    void shouldPreferNewlineOverCloserSentenceAndClausePunctuation() {
        String text = "heading\nfirst sentence; second clause, tail";
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 100)).isEqualTo(text.indexOf('\n') + 1);
    }

    /** 验证强标点优先，同优先级选择最近位置。 */
    @Test
    void shouldPreferStrongPunctuationAndPickTheClosestOfEqualPriority() {
        String text = "one\u3002two\uFF1Bthree\uFF01four\uFF0Ctail";
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 100)).isEqualTo(text.indexOf('\uFF01') + 1);
        for (String mark : new String[] { "\u3002", "\uFF01", "\uFF1F", "\uFF1B", ".", "!", "?", ";" }) {
            String sample = "sentence" + mark + " clause, tail";
            assertThat(PunctuationBoundary.before(sample, sample.length(), 0, 100)).isEqualTo(
                "sentence".length() + mark.length()
            );
        }
    }

    /** 验证仅在没有强边界时采用弱标点。 */
    @Test
    void shouldUseWeakPunctuationOnlyWhenNoStrongerBoundaryExists() {
        String text = "one\uFF0Ctwo\u3001three\uFF1Atail";
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 100)).isEqualTo(text.indexOf('\uFF1A') + 1);
    }

    /** 验证禁用或窗口内没有合法标点时保留原边界。 */
    @Test
    void shouldLeaveTheOriginalBoundaryWhenDisabledOrNoLegalPunctuationIsInTheWindow() {
        String text = "head;abcdefghij";
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 5)).isEqualTo(text.length());
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 0)).isEqualTo(text.length());
        assertThat(PunctuationBoundary.before(text, text.length(), 5, 100)).isEqualTo(text.length());
        assertThat(PunctuationBoundary.before("unbroken", 8, 0, 100)).isEqualTo(8);
    }

    /** 验证按 Unicode 码点计数且不拆开代理对。 */
    @Test
    void shouldCountUnicodeCodePointsWithoutSplittingSurrogatePairs() {
        String text = "a;\uD83D\uDE80\uD83D\uDE80zz";
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 4)).isEqualTo(text.length());
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 5)).isEqualTo(2);
    }

    /** 验证 CRLF 不被拆开并支持其他换行符。 */
    @Test
    void shouldKeepCrLfTogetherAndSupportOtherLineSeparators() {
        String text = "heading\r\ntail";
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 100)).isEqualTo(text.indexOf('\n') + 1);
        for (String newline : new String[] { "\r", "\u2028", "\u2029" }) {
            String sample = "head" + newline + "tail";
            assertThat(PunctuationBoundary.before(sample, sample.length(), 0, 100)).isEqualTo(5);
        }
    }

    /** 验证小数和版本号中的点不被当作句末。 */
    @Test
    void shouldNotMistakeDecimalOrVersionDotsForSentenceEnds() {
        String text = "DeepSeek-V3.2 value 1.23 and e.g.example tail";
        assertThat(PunctuationBoundary.before(text, text.length(), 0, 100)).isEqualTo(text.length());
        assertThat(PunctuationBoundary.before("Sentence. tail", 14, 0, 100)).isEqualTo(9);
    }
}
