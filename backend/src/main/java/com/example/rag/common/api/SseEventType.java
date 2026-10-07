package com.example.rag.common.api;

import java.util.Locale;

public enum SseEventType {
    STATUS,
    CONTENT,
    CITATION,
    USAGE,
    VERIFICATION,
    DONE,
    ERROR;

    /** 返回 SSE 协议使用的小写事件名称。 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 判断事件是否标志本次 SSE 流结束。 */
    public boolean terminal() {
        return this == DONE || this == ERROR;
    }
}
