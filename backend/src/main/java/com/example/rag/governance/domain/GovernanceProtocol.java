package com.example.rag.governance.domain;

/** 缓存命中在请求追踪和 SSE 用量里显式记录。 */
public final class GovernanceProtocol {

    public static final String CACHE_HIT = "CACHE_HIT";

    /** 协议容器不能实例化。 */
    private GovernanceProtocol() {
    }
}
