package com.example.rag.governance.domain;

/** 请求追踪的运行与终态，查询绑定拒绝未知状态。 */
public enum RequestLogStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
