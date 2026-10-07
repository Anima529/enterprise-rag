package com.example.rag.document.service.parser;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class TextSource {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private TextSource() {
    }

    /** 严格解码 UTF-8 文本并保留原始行顺序与行号。 */
    static String[] lines(InputStream input, ParseBudget budget) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(budget.read(input)))
            .toString();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        budget.limit(text.length(), budget.documents().maxTextCharacters());
        if (
            text
                .codePoints()
                .anyMatch(c -> c == 0 || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
        ) {
            throw ParseBudget.invalid();
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }
}
