package com.example.rag.auth.mapper.seed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DevSeedMapper {
    /** 幂等插入开发部门，已有编码不重复创建。 */
    int insertDepartment(@Param("code") String code, @Param("name") String name);

    /** 查询开发部门的有效记录 ID。 */
    Long activeDepartmentId(String code);

    /** 幂等插入开发账号，保留已有账号的密码和角色。 */
    int insertUser(
        @Param("username") String username,
        @Param("hash") String hash,
        @Param("departmentId") long departmentId,
        @Param("role") String role
    );
}
