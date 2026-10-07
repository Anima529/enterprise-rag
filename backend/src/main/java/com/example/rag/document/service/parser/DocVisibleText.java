package com.example.rag.document.service.parser;

import java.util.ArrayDeque;
import java.util.Deque;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;

/** Word 域可能跨段落；保留缓存的显示正文，跳过域指令。 */
final class DocVisibleText {

    private final Deque<Boolean> fields = new ArrayDeque<>();
    private int instructions;

    /** 提取段落可见文字，排除控制标记和字段指令。 */
    String paragraph(Paragraph paragraph, ParseBudget budget) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < paragraph.numCharacterRuns(); index++) {
            budget.check();
            CharacterRun run = paragraph.getCharacterRun(index);
            if (run.isMarkedDeleted() || run.isVanished()) {
                continue;
            }
            String value = run.text();
            for (int offset = 0; offset < value.length(); offset++) {
                budget.check();
                char character = value.charAt(offset);
                switch (character) {
                    case '\u0013' -> {
                        fields.push(false);
                        instructions++;
                    }
                    case '\u0014' -> {
                        if (!fields.isEmpty() && !fields.peek()) {
                            fields.pop();
                            fields.push(true);
                            instructions--;
                        }
                    }
                    case '\u0015' -> {
                        if (!fields.isEmpty() && !fields.pop()) {
                            instructions--;
                        }
                    }
                    default -> {
                        if (instructions == 0) {
                            // 段落和单元格结束符、图片和批注标记属于 Word 结构，不作为正文输出。
                            if (
                                character == '\r' ||
                                character == '\u0007' ||
                                character == '\u0001' ||
                                character == '\u0002'
                            ) {
                                continue;
                            }
                            text.append(character == '\u000b' || character == '\u000c' ? '\n' : character);
                            budget.pendingCharacters(text.length());
                        }
                    }
                }
            }
        }
        return text.toString().strip();
    }
}
