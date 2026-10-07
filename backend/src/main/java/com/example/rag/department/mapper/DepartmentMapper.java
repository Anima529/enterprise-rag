package com.example.rag.department.mapper;

import com.example.rag.department.dto.DepartmentResponse;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DepartmentMapper {
    /** 按部门 ID 分页读取未删除的部门。 */
    List<DepartmentResponse> list(@Param("limit") int limit, @Param("offset") long offset);

    /** 统计未删除的部门记录数。 */
    long count();
}
