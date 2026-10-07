package com.example.rag.rag.retrieve.initializer;

import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.example.rag.rag.retrieve.mapper.KeywordIndexMapper;
import com.example.rag.rag.retrieve.support.KeywordAnalyzer;
import java.util.List;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;

/** 历史 Chunk 补齐不调用模型；每次更新独立提交，失败会阻止就绪并允许重启续跑。 */
@Component
public class KeywordIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndexInitializer.class);
    private final KeywordIndexMapper mapper;
    private final RetrievalProperties config;

    /** 配置历史词项补齐的持久化入口和批量上限。 */
    public KeywordIndexInitializer(KeywordIndexMapper mapper, RetrievalProperties config) {
        this.mapper = mapper;
        this.config = config;
    }

    /** 启动时补齐全部历史 Child，已完成且正文一致的行不会重复更新。 */
    @Override
    public void run(ApplicationArguments arguments) {
        int updated = 0;
        List<KeywordIndexMapper.Pending> pending = mapper.pending(KeywordAnalyzer.VERSION, config.backfillBatchSize());
        while (!pending.isEmpty()) {
            for (KeywordIndexMapper.Pending chunk : pending) {
                updated += mapper.update(
                    chunk.id(),
                    chunk.content(),
                    KeywordAnalyzer.text(chunk.content()),
                    KeywordAnalyzer.VERSION
                );
            }
            pending = mapper.pending(KeywordAnalyzer.VERSION, config.backfillBatchSize());
        }
        log.info("keyword index ready, analyzer={}, updatedChildCount={}", KeywordAnalyzer.VERSION, updated);
    }
}
