package com.example.rag.document.service.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/** 用于可复现的索引 Token 预算，不代表 DashScope 的计费分词器。 */
@Component
public class ChunkTokenizer {

    public static final String NAME = "cl100k_base";
    /** cl100k 的保守字符扫描窗口，仅限制编码成本，不改变实际 Token 判定。 */
    public static final int MAX_CHARACTERS_PER_TOKEN = 16;
    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    /** 计算文本的 Token 数，用于控制 Chunk 和上下文预算。 */
    public int count(String text) {
        return encoding.countTokensOrdinary(text);
    }

    /** 寻找指定 Token 预算内的前缀终点，保留完整 Unicode 字符。 */
    public int prefixEnd(String text, int start, int tokens) {
        // 即使单个段落很大，也限制每次编码操作的输入范围。
        int end = Math.min(text.length(), start + tokens * MAX_CHARACTERS_PER_TOKEN);
        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        String candidate = text.substring(start, end);
        return start + encoding.decode(encoding.encodeOrdinary(candidate, tokens).getTokens()).length();
    }
}
