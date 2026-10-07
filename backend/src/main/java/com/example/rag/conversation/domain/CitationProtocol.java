package com.example.rag.conversation.domain;

/** 引用编号与无效标记在流式过滤、入模上下文和核验中保持一致。 */
public final class CitationProtocol {

    public static final String ID_PREFIX = "C";
    public static final String INVALID_MARKER = "[引用无效]";
    public static final char ID_PREFIX_CHARACTER = 'C';
    public static final int MAX_MARKER_PREFIX_CHARACTERS = 24;
    public static final java.util.regex.Pattern MARKER_PATTERN = java.util.regex.Pattern.compile("\\[C([0-9]+)\\]");

    /** 常量容器不参与业务对象实例化。 */
    private CitationProtocol() {
    }
}
