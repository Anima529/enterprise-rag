package com.example.rag.rag.retrieve.support;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** 移植 Phase 0 的词项契约；不依赖数据库分词扩展或外部词典。 */
public final class KeywordAnalyzer {

    public static final String VERSION = "han-bigram-v1";
    private static final Pattern CLAUSE = Pattern.compile("第\\s*(\\d+(?:\\.\\d+)*)\\s*条");
    private static final Pattern RUN = Pattern.compile("\\p{IsHan}+|[a-z0-9]+(?:[._-][a-z0-9]+)*");

    /** 分析器无可变状态，仅提供静态词项转换。 */
    private KeywordAnalyzer() {
    }

    /** 归一化全角字符并保护条款号，按 Unicode 码点生成中文双字词项。 */
    public static List<String> terms(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher clauses = CLAUSE.matcher(normalized);
        StringBuffer remaining = new StringBuffer();
        while (clauses.find()) {
            terms.add("clause" + clauses.group(1).replace('.', 'x'));
            clauses.appendReplacement(remaining, " ");
        }
        clauses.appendTail(remaining);
        Matcher runs = RUN.matcher(remaining);
        while (runs.find()) {
            String run = runs.group();
            int[] characters = run.codePoints().toArray();
            if (Character.UnicodeScript.of(characters[0]) != Character.UnicodeScript.HAN) {
                terms.add(run);
            } else if (characters.length == 1) {
                terms.add(run);
            } else {
                for (int index = 0; index < characters.length - 1; index++) {
                    terms.add(new String(characters, index, 2));
                }
            }
        }
        return List.copyOf(terms);
    }

    /** 将去重词项连接为供 PostgreSQL simple 分析器使用的文本。 */
    public static String text(String content) {
        return String.join(" ", terms(content));
    }

    /** 用 OR 组合安全词项，使自然语言问题中的助词不阻断精确术语召回。 */
    public static String query(String content) {
        return terms(content)
            .stream()
            .map(term -> "'" + term + "'")
            .reduce((left, right) -> left + " | " + right)
            .orElse("");
    }
}
