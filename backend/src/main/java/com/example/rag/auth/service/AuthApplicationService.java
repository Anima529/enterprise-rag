package com.example.rag.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.dto.LoginRequest;
import com.example.rag.auth.dto.LoginResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.dto.UserResponse;
import com.example.rag.user.mapper.UserMapper;
import com.example.rag.user.service.UserApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);
    private final UserMapper mapper;
    private final UserApplicationService users;
    private final PasswordService passwords;

    /** 配置登录所需的账号查询、用户状态和密码校验依赖。 */
    public AuthApplicationService(UserMapper mapper, UserApplicationService users, PasswordService passwords) {
        this.mapper = mapper;
        this.users = users;
        this.passwords = passwords;
    }

    /** 统一校验账号与密码，创建登录会话并返回可公开的用户信息。 */
    public LoginResponse login(LoginRequest request) {
        if (!passwords.validLength(request.password())) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        UserAccount account = mapper.findByUsername(request.username());
        boolean matches = passwords.matches(request.password(), account == null ? null : account.passwordHash());
        if (!matches || account == null || !account.active()) {
            log.info("login rejected, code={}", ErrorCode.AUTH_401_INVALID_CREDENTIALS);
            throw new BusinessException(ErrorCode.AUTH_401_INVALID_CREDENTIALS);
        }
        StpUtil.login(account.id());
        log.info("login succeeded, userId={}", account.id());
        return new LoginResponse(StpUtil.getTokenValue(), UserResponse.from(account));
    }

    /** 查询当前会话对应的有效用户，避免继续使用已停用账号。 */
    public UserResponse me() {
        return UserResponse.from(users.requireActive(StpUtil.getLoginIdAsLong()));
    }

    /** 撤销当前登录会话，保留同一账号的其他会话。 */
    public void logout() {
        long id = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        log.info("logout succeeded, userId={}", id);
    }
}
