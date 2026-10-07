package com.example.rag.user.domain;

import com.example.rag.common.security.AccountProtocol;

/** 内部数据库投影，不通过 Controller 返回。 */
public record UserAccount(
    Long id,
    String username,
    String passwordHash,
    Long departmentId,
    String departmentName,
    String role,
    String status,
    boolean deleted,
    String departmentStatus,
    boolean departmentDeleted
) {
    /** 判断账号是否未删除且处于启用状态。 */
    public boolean active() {
        return (
            !deleted &&
            !departmentDeleted &&
            AccountProtocol.ACTIVE.equals(status) &&
            AccountProtocol.ACTIVE.equals(departmentStatus)
        );
    }

    /** 输出脱敏账号信息，避免密码摘要进入日志。 */
    @Override
    public String toString() {
        return "UserAccount[id=" + id + "]";
    }
}
