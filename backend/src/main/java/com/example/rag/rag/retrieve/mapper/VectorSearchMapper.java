package com.example.rag.rag.retrieve.mapper;

import com.example.rag.common.security.RetrievalScope;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VectorSearchMapper {
    /** 在最新账号、ACL、版本、模型与内容校验下执行授权向量检索。 */
    List<VectorSearchRepository.Hit> search(
        @Param("scope") RetrievalScope scope,
        @Param("vector") String vector,
        @Param("topK") int topK,
        @Param("model") String model,
        @Param("dimensions") int dimensions
    );
}
