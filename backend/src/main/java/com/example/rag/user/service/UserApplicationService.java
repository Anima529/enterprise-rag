package com.example.rag.user.service;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.dto.UserResponse;
import com.example.rag.user.mapper.UserMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserApplicationService {

    private final UserMapper mapper;

    /** 配置账号状态与用户目录查询依赖。 */
    public UserApplicationService(UserMapper mapper) {
        this.mapper = mapper;
    }

    /** 查询账号并拒绝不存在、停用或已删除的主体。 */
    public UserAccount requireActive(long id) {
        UserAccount account = mapper.findById(id);
        if (account == null || !account.active()) {
            throw new BusinessException(ErrorCode.AUTH_401_UNAUTHENTICATED);
        }
        return account;
    }

    /** 根据账号最新状态返回角色列表。 */
    public List<String> roles(long id) {
        UserAccount account = mapper.findById(id);
        return account != null && account.active() ? List.of(account.role()) : List.of();
    }

    /** 分页查询未删除账号并转换为公开用户信息。 */
    public PageResponse<UserResponse> list(PageRequest page) {
        return new PageResponse<>(
            mapper.list(page.pageSize(), page.offset()).stream().map(UserResponse::from).toList(),
            mapper.count(),
            page.page(),
            page.pageSize()
        );
    }
}
