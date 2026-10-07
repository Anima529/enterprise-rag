package com.example.rag.document.service.upload;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;

/** 打开真实 Word 二进制文档，不执行宏、嵌入对象或外部链接。 */
public final class LegacyWordContent {

    private static final String WORD_DOCUMENT_STREAM = "WordDocument";
    private static final int FIB_HEADER_BYTES = 32;
    private static final int FIB_SIGNATURE = 0xA5EC;
    private static final int FIB_FLAGS_OFFSET = 10;
    private static final int ENCRYPTION_OR_OBFUSCATION_MASK = 0x8100;

    private static final Set<String> MACRO_ENTRIES = Set.of("macros", "vba", "_vba_project_cur", "_vba_project");

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private LegacyWordContent() {
    }

    /** 安全打开旧版 Word 二进制容器并校验内容结构。 */
    public static HWPFDocument open(byte[] bytes, DocumentProperties properties, Runnable check) throws IOException {
        check.run();
        if (FileMagic.valueOf(bytes) != FileMagic.OLE2) {
            throw invalid();
        }
        POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(bytes));
        try {
            validateEntries(filesystem.getRoot(), properties, check);
            // Word FIB 基础结构：字节 0 为签名，字节 10 的字包含加密或混淆标志。
            try (DocumentInputStream stream = filesystem.createDocumentInputStream(WORD_DOCUMENT_STREAM)) {
                byte[] header = stream.readNBytes(FIB_HEADER_BYTES);
                if (
                    header.length != FIB_HEADER_BYTES ||
                    LittleEndian.getUShort(header, 0) != FIB_SIGNATURE ||
                    (LittleEndian.getUShort(header, FIB_FLAGS_OFFSET) & ENCRYPTION_OR_OBFUSCATION_MASK) != 0
                ) {
                    throw invalid();
                }
            }
            check.run();
            HWPFDocument document = new HWPFDocument(filesystem);
            if (document.characterLength() > properties.maxTextCharacters()) {
                throw new BusinessException(ErrorCode.DOC_413_TOO_LARGE);
            }
            check.run();
            return document;
        } catch (IOException | RuntimeException exception) {
            filesystem.close();
            throw exception;
        }
    }

    /** 限制 OLE 容器条目数量并拒绝宏存储项。 */
    private static void validateEntries(DirectoryEntry root, DocumentProperties properties, Runnable check) {
        Deque<DirectoryEntry> directories = new ArrayDeque<>();
        directories.push(root);
        int count = 0;
        while (!directories.isEmpty()) {
            check.run();
            for (Entry entry : directories.pop()) {
                check.run();
                // OLE 存储与 OOXML ZIP 复用现有 Office 容器条目数量上限。
                if (++count > properties.maxZipEntries()) {
                    throw new BusinessException(ErrorCode.DOC_413_TOO_LARGE);
                }
                if (MACRO_ENTRIES.contains(entry.getName().toLowerCase(Locale.ROOT))) {
                    throw invalid();
                }
                if (entry.isDirectoryEntry()) {
                    directories.push((DirectoryEntry) entry);
                }
            }
        }
    }

    /** 构造旧版 Word 内容校验失败的统一错误。 */
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.DOC_422_INVALID_CONTENT);
    }
}
