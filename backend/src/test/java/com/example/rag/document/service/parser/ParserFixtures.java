package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.support.DocumentFixtures;
import com.example.rag.document.support.LegacyDocFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 可复现的合成验收文件，不打包到生产应用中。 */
public final class ParserFixtures {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private ParserFixtures() {
    }

    /** 生成包含指定段落或表格结构的 DOCX 测试样本。 */
    public static byte[] docx() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.DOCX));
        entries.put(
            "word/document.xml",
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>中文公司制度</w:t></w:r></w:p>" +
                "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t>差旅报销</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>员工须在五个工作日内提交凭证。</w:t></w:r></w:p>" +
                "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>交通费</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>100 元</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
                "</w:body></w:document>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 生成或检查文档测试样本，供人工验证夹具使用。 */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Files.write(directory.resolve("sample.pdf"), DocumentParsingServiceTest.pdf(3, true));
        Files.write(directory.resolve("scanned.pdf"), DocumentParsingServiceTest.pdf(1, false));
        Files.write(directory.resolve("sample.docx"), docx());
        Files.write(directory.resolve("sample.doc"), LegacyDocFixtures.sample());
        Files.write(directory.resolve("sample.xlsx"), DocumentParsingServiceTest.excel());
        Files.writeString(
            directory.resolve("sample.txt"),
            "\uFEFF中文制度\r\n员工须在五个工作日内提交凭证。\r\n\r\n第二段：保留原文件行号。\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            directory.resolve("sample.md"),
            "中文制度\n========\n\n## 差旅报销\n\n- 交通费：100 元\n- 提交凭证\n\n| 部门 | 金额 |\n| --- | --- |\n| 研发 | 100 |\n\n```java\nint 金额 = 100;\n```\n\n<script>window.__phase6Executed = true</script>\n[外部链接](https://example.invalid/untrusted)\n![图片](https://example.invalid/image.png)\n",
            StandardCharsets.UTF_8
        );
    }
}
