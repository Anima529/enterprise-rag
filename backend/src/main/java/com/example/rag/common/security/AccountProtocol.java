package com.example.rag.common.security;

/** 账号与部门有效状态及系统角色，注解与服务使用同一名称。 */
public final class AccountProtocol {

    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String USER = "USER";
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    /** 常量容器不参与业务对象实例化。 */
    private AccountProtocol() {
    }
}
