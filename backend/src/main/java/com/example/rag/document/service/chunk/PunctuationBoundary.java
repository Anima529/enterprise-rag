package com.example.rag.document.service.chunk;

/** 在有限范围内按 Unicode 码点安全地向后搜索，标点归属于前一个分块。 */
final class PunctuationBoundary {

    private static final int PARAGRAPH_BOUNDARY_PRIORITY = 3;

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private PunctuationBoundary() {
    }

    /** 在允许的回看窗口中优先选择较强标点作为切分边界。 */
    static int before(String text, int limit, int minimum, int lookback) {
        if (lookback == 0 || limit <= minimum) {
            return limit;
        }
        int cursor = limit;
        int selected = limit;
        int highest = 0;
        for (int scanned = 0; scanned < lookback && cursor > minimum; scanned++) {
            int boundary = cursor;
            cursor -= Character.charCount(text.codePointBefore(cursor));
            int priority = priority(text, cursor, boundary);
            // 从后向前扫描，优先级相同时保留最近的边界。
            if (priority > highest) {
                selected = boundary;
                highest = priority;
            }
            if (highest == PARAGRAPH_BOUNDARY_PRIORITY) {
                break;
            }
        }
        return selected;
    }

    /** 评估换行、句末及弱标点优先级，避免把数字中的点当作句末。 */
    private static int priority(String text, int position, int boundary) {
        return switch (text.codePointAt(position)) {
            case '\n', '\u2028', '\u2029' -> PARAGRAPH_BOUNDARY_PRIORITY;
            case '\r' -> boundary < text.length() && text.charAt(boundary) == '\n' ? 0 : PARAGRAPH_BOUNDARY_PRIORITY;
            case '\u3002', '\uFF01', '\uFF1F', '\uFF1B', '!', '?', ';' -> 2;
            case '.' -> position > 0 &&
            boundary < text.length() &&
            Character.isLetterOrDigit(text.codePointBefore(position)) &&
            Character.isLetterOrDigit(text.codePointAt(boundary))
                ? 0
                : 2;
            case '\uFF0C', '\u3001', '\uFF1A', ',', ':' -> 1;
            default -> 0;
        };
    }
}
