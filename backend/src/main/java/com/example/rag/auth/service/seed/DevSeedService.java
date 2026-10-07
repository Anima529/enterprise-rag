package com.example.rag.auth.service.seed;

import com.example.rag.auth.mapper.seed.DevSeedMapper;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.security.AccountProtocol;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.mapper.UserMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("dev")
public class DevSeedService {

    private static final String ALPHA_DEPARTMENT_CODE = "ALPHA";
    private static final String BETA_DEPARTMENT_CODE = "BETA";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ALPHA_USERNAME = "alpha.user";
    private static final String BETA_USERNAME = "beta.user";
    private static final int MIN_SEED_PASSWORD_CHARACTERS = 6;

    private final DevSeedMapper mapper;
    private final UserMapper users;
    private final PasswordService passwords;

    /** 配置幂等种子写入和密码摘要依赖。 */
    public DevSeedService(DevSeedMapper mapper, UserMapper users, PasswordService passwords) {
        this.mapper = mapper;
        this.users = users;
        this.passwords = passwords;
    }

    /** 在同一事务中初始化两个部门及三个演示账号。 */
    @Transactional
    public void initialize(String adminPassword, String alphaPassword, String betaPassword) {
        validate(adminPassword);
        validate(alphaPassword);
        validate(betaPassword);
        mapper.insertDepartment(ALPHA_DEPARTMENT_CODE, "研发部");
        mapper.insertDepartment(BETA_DEPARTMENT_CODE, "人事部");
        Long alpha = mapper.activeDepartmentId(ALPHA_DEPARTMENT_CODE);
        Long beta = mapper.activeDepartmentId(BETA_DEPARTMENT_CODE);
        if (alpha == null || beta == null) {
            throw new IllegalStateException("Development seed departments are inactive");
        }
        insert(ADMIN_USERNAME, adminPassword, alpha, AccountProtocol.SYSTEM_ADMIN);
        insert(ALPHA_USERNAME, alphaPassword, alpha, AccountProtocol.USER);
        insert(BETA_USERNAME, betaPassword, beta, AccountProtocol.USER);
    }

    /** 拒绝缺失或超过 BCrypt 字节限制的种子密码。 */
    private void validate(String password) {
        if (
            password == null ||
            password.isBlank() ||
            password.length() < MIN_SEED_PASSWORD_CHARACTERS ||
            !passwords.validLength(password)
        ) {
            throw new IllegalStateException(
                "Development seed requires three passwords of at least 6 characters and at most 72 UTF-8 bytes"
            );
        }
    }

    /** 校验种子密码并保存摘要，避免覆盖已有账号。 */
    private void insert(String username, String password, long departmentId, String role) {
        UserAccount existing = users.findByUsername(username);
        if (existing == null) {
            mapper.insertUser(username, passwords.hash(password), departmentId, role);
        }
        UserAccount account = users.findByUsername(username);
        if (
            account == null ||
            !account.active() ||
            account.departmentId() != departmentId ||
            !role.equals(account.role())
        ) {
            throw new IllegalStateException("Development seed identity conflict; existing accounts were not modified");
        }
    }
}
