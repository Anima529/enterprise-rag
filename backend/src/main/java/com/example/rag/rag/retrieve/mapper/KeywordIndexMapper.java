package com.example.rag.rag.retrieve.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KeywordIndexMapper {
    record Pending(long id, String content) {}

    /** 分批读取词项缺失、过期或正文已变化的有效 Child。 */
    List<Pending> pending(@Param("analyzer") String analyzer, @Param("limit") int limit);

    /** 仅更新正文未变化的 Child，避免启动补齐覆盖并发重建的词项。 */
    int update(
        @Param("id") long id,
        @Param("content") String content,
        @Param("terms") String terms,
        @Param("analyzer") String analyzer
    );
}
