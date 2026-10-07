package com.example.rag.document.exception.index;

public class LostTaskLease extends RuntimeException {

    /** 标记任务租约已失效，阻止旧 Worker 继续提交。 */
    public LostTaskLease() {
        super("Task lease is no longer valid");
    }
}
