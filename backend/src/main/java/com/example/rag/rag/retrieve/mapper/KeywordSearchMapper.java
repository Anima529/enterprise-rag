package com.example.rag.rag.retrieve.mapper;

import com.example.rag.common.security.RetrievalScope;
import com.example.rag.rag.retrieve.service.KeywordRetriever;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KeywordSearchMapper {
    /** FTS 排名仅在当前授权的可见 Child 上执行，同分按 Chunk ID 稳定排序。 */
    List<KeywordRetriever.KeywordHit> search(
        @Param("scope") RetrievalScope scope,
        @Param("query") String query,
        @Param("topK") int topK,
        @Param("analyzer") String analyzer
    );
}
