package com.example.rag.document.service.parser;

import java.util.ArrayList;
import java.util.List;

final class HeadingPath {

    static final int MAX_HEADING_LEVELS = 9;

    private final String[] titles = new String[MAX_HEADING_LEVELS];

    /** 更新当前标题并清空更深层级，避免沿用上一章节的路径。 */
    void update(int level, String text) {
        titles[level - 1] = text;
        for (int i = level; i < titles.length; i++) {
            titles[i] = null;
        }
    }

    /** 返回当前有效标题组成的不可变路径。 */
    List<String> titles() {
        List<String> path = new ArrayList<>();
        for (String title : titles) {
            if (title != null) {
                path.add(title);
            }
        }
        return List.copyOf(path);
    }

    /** 返回当前一级标题作为章节。 */
    String chapter() {
        return titles[0];
    }

    /** 将有效标题路径组合为可读的节位置。 */
    String section() {
        return titles().isEmpty() ? null : String.join(" / ", titles());
    }
}
