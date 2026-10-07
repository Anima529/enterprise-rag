package com.example.rag.department.service;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.department.dto.DepartmentResponse;
import com.example.rag.department.mapper.DepartmentMapper;
import org.springframework.stereotype.Service;

@Service
public class DepartmentApplicationService {

    private final DepartmentMapper mapper;

    /** 配置部门分页查询依赖。 */
    public DepartmentApplicationService(DepartmentMapper mapper) {
        this.mapper = mapper;
    }

    /** 分页查询有效部门并附带总数。 */
    public PageResponse<DepartmentResponse> list(PageRequest page) {
        return new PageResponse<>(
            mapper.list(page.pageSize(), page.offset()),
            mapper.count(),
            page.page(),
            page.pageSize()
        );
    }
}
