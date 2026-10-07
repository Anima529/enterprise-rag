package com.example.rag.conversation.support;

import com.example.rag.conversation.domain.CitationProtocol;
import java.util.Set;

/** 流式引用标记可跨任意增量边界；未完成的标记不能提前交付或落库。 */
public class CitationMarkerFilter {

    private final Set<String> allowed;
    private final StringBuilder pending = new StringBuilder();

    /** 复制本次实际入模引用白名单，防止后续修改影响流式校验。 */
    public CitationMarkerFilter(Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    /** 保留合法标记，将未获授权的编号替换为可见提示，不生成虚假来源。 */
    public String append(String delta) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < delta.length(); index++) {
            char character = delta.charAt(index);
            if (pending.isEmpty()) {
                if (character == '[') {
                    pending.append(character);
                } else {
                    output.append(character);
                }
                continue;
            }
            if (isPrefixCharacter(character)) {
                pending.append(character);
            } else if (
                character == ']' && pending.length() > 2 && pending.charAt(1) == CitationProtocol.ID_PREFIX_CHARACTER
            ) {
                String citationId = pending.substring(1);
                output.append(allowed.contains(citationId) ? pending + "]" : CitationProtocol.INVALID_MARKER);
                pending.setLength(0);
            } else {
                output.append(pending);
                pending.setLength(0);
                if (character == '[') {
                    pending.append(character);
                } else {
                    output.append(character);
                }
            }
        }
        return output.toString();
    }

    /** 只缓存有限长度的规范编号前缀，普通 Markdown 方括号立即透传。 */
    private boolean isPrefixCharacter(char character) {
        return (
            (pending.length() == 1 && character == CitationProtocol.ID_PREFIX_CHARACTER) ||
            (pending.length() >= 2 &&
                pending.length() < CitationProtocol.MAX_MARKER_PREFIX_CHARACTERS &&
                character >= '0' &&
                character <= '9')
        );
    }

    /** 结束时将不完整编号提示为无效引用，普通方括号保持原文。 */
    public String finish() {
        String remainder =
            pending.length() >= 2 && pending.charAt(1) == CitationProtocol.ID_PREFIX_CHARACTER
                ? CitationProtocol.INVALID_MARKER
                : pending.toString();
        pending.setLength(0);
        return remainder;
    }
}
