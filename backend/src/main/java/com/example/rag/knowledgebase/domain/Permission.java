package com.example.rag.knowledgebase.domain;

public enum Permission {
    READ,
    WRITE,
    ADMIN;

    /** 按权限继承规则判断是否包含所需权限。 */
    public boolean includes(Permission required) {
        return ordinal() >= required.ordinal();
    }
}
