package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.support.DocumentFixtures;
import java.util.LinkedHashMap;
import java.util.Map;

/** 同一工作表中包含缓存公式、带样式空行、汇总行及独立表格的测试文件。 */
public final class ExcelSummaryFixtures {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private ExcelSummaryFixtures() {
    }

    /** 创建汇总解析用例所需的资源预算。 */
    public static byte[] budget(boolean blankRow, String label, boolean numericSummary) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put("xl/workbook.xml", entries.get("xl/workbook.xml").replace("测试", "部门预算"));
        String header =
            "<row r=\"6\">" +
            text("A6", "部门") +
            text("B6", "季度预算") +
            text("C6", "已验收应付") +
            text("D6", "预算余量") +
            text("E6", "执行率") +
            text("F6", "预算状态") +
            text("G6", "责任岗位") +
            "</row>";
        String summary = numericSummary
            ? formula("B14", "SUM(B7:B12)", "560000") +
              formula("C14", "SUM(C7:C12)", "531000") +
              formula("D14", "B14-C14", "29000") +
              formula("E14", "C14/B14", "0.9482142857142857")
            : text("B14", "预算") + text("C14", "执行") + text("D14", "剩余") + text("E14", "比例");
        entries.put(
            "xl/worksheets/sheet1.xml",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                header +
                "<row r=\"7\">" +
                text("A7", "研发部") +
                number("B7", "160000") +
                number("C7", "170000") +
                number("D7", "-10000") +
                "</row>" +
                "<row r=\"12\">" +
                text("A12", "其他部门") +
                number("B12", "400000") +
                number("C12", "361000") +
                number("D12", "39000") +
                "</row>" +
                (blankRow ? "<row r=\"13\"><c r=\"A13\" s=\"1\"/><c r=\"G13\" s=\"1\"/></row>" : "") +
                "<row r=\"14\">" +
                text("A14", label) +
                summary +
                "</row>" +
                "<row r=\"16\"/><row r=\"17\">" +
                text("A17", "项目") +
                text("B17", "成本") +
                "</row>" +
                "<row r=\"18\">" +
                text("A18", "云资源") +
                number("B18", "1234") +
                "</row>" +
                "<row r=\"19\"/><row r=\"20\">" +
                text("A20", "合计") +
                number("B20", "1234") +
                "</row>" +
                "</sheetData></worksheet>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 构造含资产明细和横向指标的 Excel 测试样本。 */
    public static byte[] assets(boolean blankRow, boolean mergedLabel) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put("xl/workbook.xml", entries.get("xl/workbook.xml").replace("测试", "资产台账"));
        entries.put(
            "xl/worksheets/sheet1.xml",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"6\">" +
                text("A6", "资产编号") +
                text("B6", "资产类别") +
                text("D6", "所属部门") +
                text("G6", "原值 元") +
                "</row>" +
                "<row r=\"7\">" +
                text("A7", "AS-0009") +
                text("B7", "办公电脑") +
                text("D7", "客户服务部") +
                number("G7", "7200") +
                "</row>" +
                (blankRow ? "<row r=\"19\"><c r=\"A19\"/><c r=\"K19\"/></row>" : "") +
                "<row r=\"20\">" +
                text("A20", "资产数量") +
                formula("B20", "COUNTA(A7:A18)", "12") +
                text("D20", "原值合计 元") +
                "<c r=\"E20\"/><c r=\"F20\"/>" +
                formula("G20", "SUM(G7:G18)", "562400") +
                "<c r=\"K20\"/></row>" +
                "<row r=\"22\"/><row r=\"23\">" +
                text("A23", "部门") +
                number("B23", "2026") +
                text("C23", "月份") +
                number("D23", "2027") +
                "</row>" +
                "</sheetData>" +
                (mergedLabel ? "<mergeCells><mergeCell ref=\"D20:F20\"/></mergeCells>" : "") +
                "</worksheet>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 构造含独立正文说明的 Excel 测试样本。 */
    public static byte[] notes() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.XLSX));
        entries.put(
            "xl/worksheets/sheet1.xml",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"22\">" +
                text("A22", "按指定基准核对截止日期，未配置时由责任岗位确认。") +
                "<c r=\"K22\"/></row></sheetData></worksheet>"
        );
        return DocumentFixtures.zip(entries);
    }

    /** 构造测试正文或文本块，保留用例所需来源位置。 */
    private static String text(String cell, String value) {
        return "<c r=\"" + cell + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }

    /** 构造指定坐标的数值单元格 XML。 */
    private static String number(String cell, String value) {
        return "<c r=\"" + cell + "\"><v>" + value + "</v></c>";
    }

    /** 构造带缓存值的公式单元格 XML。 */
    private static String formula(String cell, String expression, String cached) {
        return "<c r=\"" + cell + "\"><f>" + expression + "</f><v>" + cached + "</v></c>";
    }
}
