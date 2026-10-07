package com.example.rag.common.api;

/** JSON 中的 ID 必须能由 JavaScript Number 无损表示，保留现有跨端边界。 */
public final class ApiContractLimits {

    public static final long MAX_SAFE_ID = 9_007_199_254_740_991L;

    /** 协议边界通过静态成员读取，不创建实例。 */
    private ApiContractLimits() {
    }
}
