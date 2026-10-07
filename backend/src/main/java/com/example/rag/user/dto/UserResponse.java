package com.example.rag.user.dto;

import com.example.rag.user.domain.UserAccount;
import java.util.List;

public record UserResponse(
    Long id,
    String username,
    Long departmentId,
    String departmentName,
    List<String> roles,
    String status
) {
    /** 将账号转换为公开用户响应，排除密码摘要。 */
    public static UserResponse from(UserAccount account) {
        return new UserResponse(
            account.id(),
            account.username(),
            account.departmentId(),
            account.departmentName(),
            List.of(account.role()),
            account.status()
        );
    }
}
