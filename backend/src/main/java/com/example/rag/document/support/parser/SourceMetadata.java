package com.example.rag.document.support.parser;

/** 解析、Chunk、引用与预览共享的来源元数据字段，保持持久化 JSON 契约。 */
public final class SourceMetadata {

    public static final String SOURCES = "sources";
    public static final String METADATA = "metadata";
    public static final String KIND = "kind";
    public static final String PARENT_CHUNK_INDEX = "parentChunkIndex";
    public static final String LINE_START = "lineStart";
    public static final String LINE_END = "lineEnd";
    public static final String CHAR_START = "charStart";
    public static final String CHAR_END = "charEnd";
    public static final String BLOCK_INDEX = "blockIndex";
    public static final String SHEET_NAME = "sheetName";
    public static final String ROW_START = "rowStart";
    public static final String ROW_END = "rowEnd";
    public static final String CHAPTER = "chapter";
    public static final String SECTION = "section";
    public static final String TABLE_INDEX = "tableIndex";
    public static final String TABLE_LAYOUT = "tableLayout";
    public static final String HEADER_POLICY = "headerPolicy";
    public static final String ROW_ROLE = "rowRole";
    public static final String FORMULA_POLICY = "formulaPolicy";
    public static final String COLUMN_STARTS_BY_LINE = "columnStartsByLine";
    public static final String MERGED_TITLE_ROW = "mergedTitleRow";
    public static final String CELLS = "cells";
    public static final String COLUMN = "column";
    public static final String ROW = "row";

    /** 常量容器不参与业务对象实例化。 */
    private SourceMetadata() {
    }
}
