package com.example.rag.knowledgebase.initializer;

import com.example.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 知识库模型字段描述应用的全局配置，历史向量来源由向量记录单独保存。 */
@Component
public class KnowledgeBaseModelInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseModelInitializer.class);
    private final KnowledgeBaseMapper mapper;
    private final EmbeddingProperties embedding;
    private final ChatProperties chat;

    /** 配置启动时模型信息同步所需的持久化和模型设置。 */
    public KnowledgeBaseModelInitializer(
        KnowledgeBaseMapper mapper,
        EmbeddingProperties embedding,
        ChatProperties chat
    ) {
        this.mapper = mapper;
        this.embedding = embedding;
        this.chat = chat;
    }

    /** 启动时将知识库模型配置与当前 Embedding 设置同步。 */
    @Override
    public void run(ApplicationArguments arguments) {
        int changed = mapper.synchronizeModels(embedding.model(), embedding.dimensions(), chat.model());
        log.info(
            "knowledge base model configuration synchronized, updated={}, embeddingModel={}, generationModel={}",
            changed,
            embedding.model(),
            chat.model()
        );
    }
}
