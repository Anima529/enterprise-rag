package com.example.rag.user.mapper;

import com.example.rag.user.domain.UserAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    /** 按用户名读取账号及密码摘要，供登录校验。 */
    UserAccount findByUsername(String username);

    /** 按用户 ID 读取账号最新状态与角色。 */
    UserAccount findById(long id);

    /** 分页查询未删除的用户账号。 */
    List<UserAccount> list(@Param("limit") int limit, @Param("offset") long offset);

    /** 统计未删除的用户账号数。 */
    long count();
}
