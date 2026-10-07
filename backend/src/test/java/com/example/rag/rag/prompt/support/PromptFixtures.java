package com.example.rag.rag.prompt.support;

import com.example.rag.rag.prompt.config.PromptProperties;
import com.example.rag.rag.prompt.service.PromptCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;

/** 测试使用真实生产模板，避免维护另一套与资源脱节的指令。 */
public final class PromptFixtures {

    /** 测试夹具通过静态工厂获取模板，不创建容器实例。 */
    private PromptFixtures() {
    }

    /** 读取默认提示词，资源错误直接使测试失败。 */
    public static PromptCatalog catalog() {
        try {
            return new PromptCatalog(properties());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** 按生产默认路径构造独立资源，测试可替换其中单项。 */
    public static PromptProperties properties() {
        return new PromptProperties(
            new ClassPathResource("prompts/answer.st"),
            new ClassPathResource("prompts/rewrite.st"),
            new ClassPathResource("prompts/router.st"),
            new ClassPathResource("prompts/decompose.st"),
            new ClassPathResource("prompts/hyde.st"),
            new ClassPathResource("prompts/faithfulness.st")
        );
    }
}
