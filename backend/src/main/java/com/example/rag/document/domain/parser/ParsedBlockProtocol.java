package com.example.rag.document.domain.parser;

/** 解析块类型、表格角色与来源策略，供解析、预览和 Chunk 共用。 */
public final class ParsedBlockProtocol {

    public static final String TABLE_CELL = "TABLE_CELL";
    public static final String HEADING = "HEADING";
    public static final String LIST = "LIST";
    public static final String PARAGRAPH = "PARAGRAPH";
    public static final String ROW_GROUP = "ROW_GROUP";
    public static final String TABLE_HEADER = "TABLE_HEADER";
    public static final String KEY_VALUE_SUMMARY = "KEY_VALUE_SUMMARY";
    public static final String CACHED_VALUES_ONLY = "CACHED_VALUES_ONLY";
    public static final String TABLE = "TABLE";
    public static final String CODE = "CODE";
    public static final String PAGE = "PAGE";
    public static final String PDF_TABLES_INFERRED_FROM_ALIGNED_TEXT = "PDF_TABLES_INFERRED_FROM_ALIGNED_TEXT";
    public static final String TABLE_ROW = "TABLE_ROW";
    public static final String HEADER = "HEADER";
    public static final String DATA = "DATA";
    public static final String INHERITED_TABLE_HEADER = "INHERITED_TABLE_HEADER";
    public static final String PHYSICAL_COLUMNS = "PHYSICAL_COLUMNS";
    public static final String EXPLICIT_HEADER = "EXPLICIT_HEADER";
    public static final String INFERRED_FIRST_ROW = "INFERRED_FIRST_ROW";
    public static final String TRUE = "TRUE";
    public static final String FALSE = "FALSE";
    public static final String PAGE_WARNING_PREFIX = "PAGE_";
    public static final String NO_TEXT_LAYER_SUFFIX = "_NO_TEXT_LAYER";
    public static final String REMOVED_MARGIN_PREFIX = "REPEATED_MARGIN_LINES_REMOVED_";

    /** 常量容器不参与业务对象实例化。 */
    private ParsedBlockProtocol() {
    }
}
