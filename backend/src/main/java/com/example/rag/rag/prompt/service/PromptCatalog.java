package com.example.rag.rag.prompt.service;

import com.example.rag.rag.prompt.config.PromptProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** 启动时加载全部提示词；缺失或空模板立即失败，避免运行期间静默使用另一套指令。 */
@Component
public class PromptCatalog {

    private static final String MAX_SUBQUERIES_PLACEHOLDER = "{maxSubqueries}";
    private final String answer;
    private final String rewrite;
    private final String router;
    private final String decompose;
    private final String hyde;
    private final String faithfulness;
    private final String relevance;

    /** 按 UTF-8 读取配置资源，保留原文与空白并校验拆解预算占位符。 */
    public PromptCatalog(PromptProperties properties) throws IOException {
        answer = load(properties.answer());
        rewrite = load(properties.rewrite());
        router = load(properties.router());
        decompose = load(properties.decompose());
        hyde = load(properties.hyde());
        faithfulness = load(properties.faithfulness());
        relevance = load(properties.relevance());
        if (!decompose.contains(MAX_SUBQUERIES_PLACEHOLDER)) {
            throw new IllegalArgumentException("Decompose prompt requires " + MAX_SUBQUERIES_PLACEHOLDER);
        }
    }

    /** 返回回答生成指令，内容不包含用户输入或检索证据。 */
    public String answer() {
        return answer;
    }

    /** 返回独立问题改写指令，历史由调用方单独作为数据传入。 */
    public String rewrite() {
        return rewrite;
    }

    /** 返回复杂度分类规则，路线输出仍由服务端验证。 */
    public String router() {
        return router;
    }

    /** 只替换受控的子问题数量，不将用户输入插入系统指令。 */
    public String decompose(int maxSubqueries) {
        return decompose.replace(MAX_SUBQUERIES_PLACEHOLDER, Integer.toString(maxSubqueries));
    }

    /** 返回仅供向量检索使用的假设段落生成指令。 */
    public String hyde() {
        return hyde;
    }

    /** 返回证据核验规则，回答及证据仍以独立 JSON 数据传递。 */
    public String faithfulness() {
        return faithfulness;
    }

    /** 离线 Answer Relevance Judge 只接收问题、公开答案及参考答案。 */
    public String relevance() {
        return relevance;
    }

    /** 拒绝不可读或空白资源，资源错误由启动过程直接报告。 */
    private String load(Resource resource) throws IOException {
        String content = resource.getContentAsString(StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be blank: " + resource.getDescription());
        }
        return content;
    }
}
