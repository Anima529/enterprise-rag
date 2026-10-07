package com.example.rag.usage.domain;

/** 计费基础、币种及统计范围，保留账本和接口既有字符串值。 */
public final class UsageProtocol {

    public static final String TOKENS = "TOKENS";
    public static final String CALL = "CALL";
    public static final String UNITS = "UNITS";
    public static final String CURRENCY = "CNY";
    public static final String ALL = "ALL";
    public static final String SELF = "SELF";
    public static final String REBUILT = "REBUILT";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String CANCELLED = "CANCELLED";
    public static final long TOKENS_PER_PRICE_UNIT = 1_000_000;
    public static final int COST_DECIMAL_SCALE = 10;
    public static final String PRICE_BASIS_PATTERN = TOKENS + "|" + CALL + "|" + UNITS;

    /** 常量容器不参与业务对象实例化。 */
    private UsageProtocol() {
    }
}
