package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.support.parser.SourceMetadata;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TxtDocumentParser implements DocumentParser {

    /** 声明解析器支持 TXT 格式。 */
    public DocumentFormat format() {
        return DocumentFormat.TXT;
    }

    /** 在解析预算内提取 TXT 内容并保留结构与来源位置。 */
    public ParsedDocument parse(InputStream input, ParseBudget budget) throws Exception {
        String[] lines = TextSource.lines(input, budget);
        int start = 0;
        for (int end = 0; end <= lines.length; end++) {
            budget.check();
            if (end == lines.length || lines[end].isBlank()) {
                if (end > start) {
                    budget.add(
                        new ParsedBlock(
                            String.join("\n", Arrays.copyOfRange(lines, start, end)),
                            null,
                            null,
                            null,
                            Map.of(
                                SourceMetadata.KIND,
                                ParsedBlockProtocol.PARAGRAPH,
                                SourceMetadata.LINE_START,
                                start + 1,
                                SourceMetadata.LINE_END,
                                end
                            )
                        )
                    );
                }
                start = end + 1;
            }
        }
        return new ParsedDocument(format(), budget.blocks(), List.of());
    }
}
