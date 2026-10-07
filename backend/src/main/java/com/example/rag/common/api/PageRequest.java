package com.example.rag.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PageRequest(@Min(1) Integer page, @Min(1) @Max(100) Integer pageSize) {
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 为缺省页码和页大小设置统一默认值。 */
    public PageRequest {
        if (page == null) {
            page = 1;
        }
        if (pageSize == null) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
    }

    /** 将从 1 开始的页码换算为数据库分页偏移。 */
    public long offset() {
        return (page - 1L) * pageSize;
    }
}
