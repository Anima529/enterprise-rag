package com.example.rag.common.security;

import cn.dev33.satoken.stp.StpInterface;
import com.example.rag.user.service.UserApplicationService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DatabaseRoleProvider implements StpInterface {

    private final UserApplicationService users;

    /** 配置用于实时查询账号角色的用户服务。 */
    public DatabaseRoleProvider(UserApplicationService users) {
        this.users = users;
    }

    /** 返回框架权限列表；知识库授权由独立范围校验负责。 */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /** 重新读取有效账号角色，使角色调整立即生效。 */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return users.roles(Long.parseLong(loginId.toString()));
    }
}
