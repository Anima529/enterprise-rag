package com.example.rag.conversation.domain;

/** 消息角色、生命周期和聊天阶段，持久化与 SSE 保持既有字符串值。 */
public final class ConversationProtocol {

    public static final String USER = "USER";
    public static final String ASSISTANT = "ASSISTANT";
    public static final String GENERATING = "GENERATING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RETRIEVING = "RETRIEVING";
    public static final String VERIFYING = "VERIFYING";

    /** 常量容器不参与业务对象实例化。 */
    private ConversationProtocol() {
    }
}
