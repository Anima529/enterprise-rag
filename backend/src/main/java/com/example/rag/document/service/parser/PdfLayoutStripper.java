package com.example.rag.document.service.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** 保留较宽列间距及其定位点，普通单词间空格保持原样。 */
final class PdfLayoutStripper extends PDFTextStripper {

    private final ParseBudget budget;
    private final List<List<Float>> columns = new ArrayList<>();
    private List<Float> line = new ArrayList<>();
    private long glyphs;
    private float right;
    private boolean separator;

    /** 配置按位置排序的 PDF 文本提取，保留原始列间距。 */
    PdfLayoutStripper(ParseBudget budget) throws IOException {
        this.budget = budget;
        setSortByPosition(true);
        setParagraphStart("");
        setParagraphEnd("");
        setPageStart("");
        setPageEnd("");
    }

    /** 重置当前页的列位置与行状态。 */
    @Override
    protected void startPage(PDPage page) throws IOException {
        columns.clear();
        line = new ArrayList<>();
        separator = false;
        super.startPage(page);
    }

    /** 检查累计字形资源上限后交给 PDFBox 处理。 */
    @Override
    protected void processTextPosition(TextPosition position) {
        budget.limit(++glyphs, budget.documents().maxTextCharacters());
        super.processTextPosition(position);
    }

    /** 暂存单词间隔，待下一段文字到来时判断是否为列间距。 */
    @Override
    protected void writeWordSeparator() {
        separator = true;
    }

    /** 保留普通空格，将较宽列间距记录为制表符和定位点。 */
    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        budget.check();
        if (positions.isEmpty()) {
            super.writeString(text, positions);
            return;
        }
        TextPosition first = positions.getFirst();
        if (line.isEmpty()) {
            line.add(first.getXDirAdj());
        }
        if (separator) {
            double columnGap = Math.max(
                budget.properties().pdfColumnMinGap(),
                first.getWidthOfSpace() * budget.properties().pdfColumnSpaceMultiplier()
            );
            boolean column = first.getXDirAdj() - right >= columnGap;
            if (column) {
                getOutput().write('\t');
                line.add(first.getXDirAdj());
            } else {
                super.writeWordSeparator();
            }
        }
        separator = false;
        TextPosition last = positions.getLast();
        right = last.getXDirAdj() + last.getWidthDirAdj();
        super.writeString(text, positions);
    }

    /** 保存当前行列位置并开始下一行。 */
    @Override
    protected void writeLineSeparator() throws IOException {
        columns.add(List.copyOf(line));
        line = new ArrayList<>();
        separator = false;
        super.writeLineSeparator();
    }

    /** 返回当前页各行的列起点，包括尚未换行的最后一行。 */
    List<List<Float>> columnStarts() {
        List<List<Float>> columnPositions = new ArrayList<>(columns);
        if (!line.isEmpty()) {
            columnPositions.add(List.copyOf(line));
        }
        return columnPositions;
    }
}
