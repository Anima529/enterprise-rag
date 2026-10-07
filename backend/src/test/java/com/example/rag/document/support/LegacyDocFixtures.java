package com.example.rag.document.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;

/** 真实 Word 二进制测试文件，基于 Apache POI 已授权的空白文档构造。 */
public final class LegacyDocFixtures {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private LegacyDocFixtures() {
    }

    /** 读取本地旧版 Word 样本，不请求外部资源。 */
    public static byte[] read(String name) throws Exception {
        try (InputStream input = LegacyDocFixtures.class.getResourceAsStream("/doc/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing DOC test fixture");
            }
            return input.readAllBytes();
        }
    }

    /** 构造测试正文或文本块，保留用例所需来源位置。 */
    public static byte[] text(String text) throws Exception {
        try (
            HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(read("empty.doc")));
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            document.getRange().insertBefore(text + "\r");
            document.write(output);
            return output.toByteArray();
        }
    }

    /** 返回用于旧版 Word 解析的固定样本。 */
    public static byte[] sample() throws Exception {
        byte[] bytes = text("中文公司制度\r差旅报销\r员工须在五个工作日内提交凭证。\u000c跨页仍保留在同一段落。\r");
        try (
            HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            // 构造测试文件时，为两个真实标题段落设置二进制大纲属性。
            document
                .getParagraphTable()
                .getParagraphs()
                .get(0)
                .getSprmBuf()
                .updateSprm(Paragraph.SPRM_OUTLVL, (byte) 0);
            document
                .getParagraphTable()
                .getParagraphs()
                .get(1)
                .getSprmBuf()
                .updateSprm(Paragraph.SPRM_OUTLVL, (byte) 1);
            document.write(output);
            return output.toByteArray();
        }
    }

    /** 构造加密旧版 Word 内容以验证拒绝路径。 */
    public static byte[] encrypted() throws Exception {
        try (
            POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(sample()));
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] stream;
            try (InputStream input = filesystem.createDocumentInputStream("WordDocument")) {
                stream = input.readAllBytes();
            }
            LittleEndian.putShort(stream, 10, (short) (LittleEndian.getUShort(stream, 10) | 0x0100));
            filesystem.createOrUpdateDocument(new ByteArrayInputStream(stream), "WordDocument");
            filesystem.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    /** 构造非 Word 的 OLE 容器以验证格式校验。 */
    public static byte[] otherOleDocument() throws Exception {
        try (
            POIFSFileSystem filesystem = new POIFSFileSystem();
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            filesystem.createDocument(new ByteArrayInputStream(new byte[] { 1, 2, 3 }), "Workbook");
            filesystem.writeFilesystem(output);
            return output.toByteArray();
        }
    }
}
