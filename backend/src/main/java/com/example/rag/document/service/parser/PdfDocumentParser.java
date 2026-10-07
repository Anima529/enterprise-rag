package com.example.rag.document.service.parser;

import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedBlockProtocol;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.support.parser.SourceMetadata;
import java.io.InputStream;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentParser implements DocumentParser {

    /** 声明解析器支持 PDF 格式。 */
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    /** 在解析预算内提取 PDF 内容并保留结构与来源位置。 */
    public ParsedDocument parse(InputStream input, ParseBudget budget) throws Exception {
        List<String[]> pages = new ArrayList<>();
        List<List<List<Float>>> columns = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (
            PDDocument pdf = Loader.loadPDF(budget.read(input), "", null, null, IOUtils.createTempFileOnlyStreamCache())
        ) {
            if (pdf.isEncrypted()) {
                throw ParseBudget.invalid();
            }
            budget.limit(pdf.getNumberOfPages(), budget.documents().maxPdfPages());
            PdfLayoutStripper stripper = new PdfLayoutStripper(budget);
            long characters = 0;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                budget.check();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf).strip();
                characters += text.length();
                budget.limit(characters, budget.documents().maxTextCharacters());
                if (text.isBlank()) {
                    warnings.add(
                        ParsedBlockProtocol.PAGE_WARNING_PREFIX + page + ParsedBlockProtocol.NO_TEXT_LAYER_SUFFIX
                    );
                }
                pages.add(text.isBlank() ? new String[0] : text.split("\\R"));
                columns.add(stripper.columnStarts());
            }
        }
        if (pages.stream().allMatch(p -> p.length == 0)) {
            throw new IndexFailure(
                ErrorCode.DOC_422_OCR_REQUIRED.name(),
                "PDF 无可用文字层，可能为扫描件；需要 OCR 后重新上传",
                false
            );
        }
        Set<String> headers = repeatedMargins(pages, true, budget);
        Set<String> footers = repeatedMargins(pages, false, budget);
        int removed = 0;
        for (int page = 0; page < pages.size(); page++) {
            String[] lines = pages.get(page);
            int first = 0;
            int last = lines.length;
            // 仅在同时包含正文的页面移除重复页眉和页脚。
            if (last > 1 && headers.contains(lines[0].strip())) {
                first++;
                removed++;
            }
            if (last - first > 1 && footers.contains(lines[last - 1].strip())) {
                last--;
                removed++;
            }
            if (last > first) {
                budget.add(
                    new ParsedBlock(
                        String.join("\n", Arrays.copyOfRange(lines, first, last)),
                        page + 1,
                        null,
                        null,
                        Map.of(
                            SourceMetadata.KIND,
                            ParsedBlockProtocol.PAGE,
                            "removedMarginLines",
                            first + lines.length - last,
                            SourceMetadata.LINE_START,
                            first + 1,
                            SourceMetadata.LINE_END,
                            last,
                            SourceMetadata.COLUMN_STARTS_BY_LINE,
                            columns
                                .get(page)
                                .subList(
                                    Math.min(first, columns.get(page).size()),
                                    Math.min(last, columns.get(page).size())
                                )
                        )
                    )
                );
            }
        }
        if (removed > 0) {
            warnings.add(ParsedBlockProtocol.REMOVED_MARGIN_PREFIX + removed);
        }
        return new ParsedDocument(format(), budget.blocks(), warnings);
    }

    /** 识别多页重复的页边文字，避免删除只出现一次的正文。 */
    private Set<String> repeatedMargins(List<String[]> pages, boolean top, ParseBudget budget) {
        Map<String, Integer> counts = new HashMap<>();
        for (String[] lines : pages) {
            budget.check();
            String edge = "";
            if (lines.length > 1) {
                int edgeIndex = top ? 0 : lines.length - 1;
                edge = lines[edgeIndex].strip();
            }
            if (!edge.contains("\t") && !edge.isEmpty()) {
                counts.merge(edge, 1, Integer::sum);
            }
        }
        int threshold = Math.max(
            budget.properties().pdfRepeatedMarginMinPages(),
            (int) Math.ceil(pages.size() * budget.properties().pdfRepeatedMarginRatio())
        );
        Set<String> repeated = new HashSet<>();
        counts.forEach((line, count) -> {
            if (!line.isBlank() && count >= threshold) {
                repeated.add(line);
            }
        });
        return repeated;
    }
}
