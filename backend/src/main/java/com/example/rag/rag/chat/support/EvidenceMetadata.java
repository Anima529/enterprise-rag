package com.example.rag.rag.chat.support;

import com.example.rag.document.support.parser.SourceMetadata;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;

/** 来源快照严格按实际入模内容裁剪，行号和表格完整性不得指向预算外的尾部。 */
public class EvidenceMetadata {

    private final ObjectMapper json;

    /** 配置存储元数据的 JSON 读取与深拷贝依赖。 */
    public EvidenceMetadata(ObjectMapper json) {
        this.json = json;
    }

    /** 截取入模正文覆盖的来源范围，并更新最后一段的字符、行号与完整表格标识。 */
    public ObjectNode clip(String metadataJson, String content) {
        try {
            ObjectNode metadata = (ObjectNode) json.readTree(metadataJson);
            ArrayNode included = json.createArrayNode();
            int offset = 0;
            for (JsonNode source : metadata.path(SourceMetadata.SOURCES)) {
                if (offset >= content.length()) {
                    break;
                }
                ObjectNode snapshot = source.deepCopy();
                if (!source.path(SourceMetadata.CHAR_START).isInt() || !source.path(SourceMetadata.CHAR_END).isInt()) {
                    included.add(snapshot);
                    continue;
                }
                int start = source.path(SourceMetadata.CHAR_START).intValue();
                int sourceLength = source.path(SourceMetadata.CHAR_END).intValue() - start;
                int includedLength = Math.min(sourceLength, content.length() - offset);
                snapshot.put(SourceMetadata.CHAR_END, start + includedLength);
                if (
                    includedLength < sourceLength &&
                    snapshot.path(SourceMetadata.METADATA) instanceof ObjectNode position
                ) {
                    position.put("tableRowComplete", false);
                    if (position.path(SourceMetadata.LINE_START).isInt()) {
                        String visible = content.substring(offset, offset + includedLength).replaceFirst("\\n$", "");
                        int lineCount = (int) visible
                            .chars()
                            .filter(character -> character == '\n')
                            .count();
                        position.put(
                            SourceMetadata.LINE_END,
                            position.path(SourceMetadata.LINE_START).intValue() + lineCount
                        );
                    }
                }
                included.add(snapshot);
                offset += sourceLength + 2;
            }
            metadata.set(SourceMetadata.SOURCES, included);
            return metadata;
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid stored chunk metadata", failure);
        }
    }
}
