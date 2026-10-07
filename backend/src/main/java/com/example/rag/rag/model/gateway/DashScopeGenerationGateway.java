package com.example.rag.rag.model.gateway;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.conversation.domain.ConversationProtocol;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.config.GenerationProperties;
import com.example.rag.rag.model.domain.ModelStage;
import com.example.rag.rag.model.domain.ProviderProtocol;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.usage.service.UsageService;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
public class DashScopeGenerationGateway implements GenerationGateway {

    private final ChatProperties config;
    private final ChatModel model;
    private final UsageService usage;
    private com.example.rag.governance.service.RedisCostGuard guard;

    /** 生产环境启用跨实例模型许可；独立 HTTP 单元测试保留原构造入口。 */
    @Autowired
    public void configureGuard(com.example.rag.governance.service.RedisCostGuard guard) {
        guard.validateTimeout(config.generationTimeout());
        this.guard = guard;
    }

    /** 配置流式生成客户端与超时，缺少密钥时保留明确失败入口。 */
    public DashScopeGenerationGateway(ChatProperties config, GenerationProperties generationConfig) {
        this(config, generationConfig, null);
    }

    /** 注入实际调用账本，保留独立 Gateway HTTP 测试构造入口。 */
    @Autowired
    public DashScopeGenerationGateway(
        ChatProperties config,
        GenerationProperties generationConfig,
        UsageService usage
    ) {
        this.config = config;
        this.usage = usage;
        String key = config.apiKey().isBlank() ? config.dashscopeApiKey() : config.apiKey();
        if (key.isBlank()) {
            model = null;
            return;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(config.generationTimeout());
        OpenAiEndpoints endpoints = OpenAiEndpoints.from(config.baseUrl());
        OpenAiApi api = new OpenAiApi(
            endpoints.baseUrl(),
            new SimpleApiKey(key),
            new LinkedMultiValueMap<>(),
            endpoints.chatPath(),
            endpoints.embeddingPath(),
            RestClient.builder().requestFactory(factory),
            WebClient.builder().clientConnector(new JdkClientHttpConnector(client)),
            new DefaultResponseErrorHandler()
        );
        RetryTemplate retry = new RetryTemplate();
        retry.setRetryPolicy(new NeverRetryPolicy());
        model = OpenAiChatModel.builder()
            .openAiApi(api)
            .retryTemplate(retry)
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(config.model())
                    .temperature(generationConfig.temperature())
                    .maxTokens(config.maxOutputTokens())
                    .streamUsage(true)
                    .internalToolExecutionEnabled(false)
                    .build()
            )
            .build();
    }

    /** 流式转发模型公开文本与实际用量，避免输出私有推理内容。 */
    public Flux<GenerationDelta> generate(GenerationRequest request) {
        if (guard != null) {
            return Flux.using(
                () -> guard.acquire(config.model()),
                permit -> generateAuthorized(request),
                com.example.rag.governance.service.RedisCostGuard.Permit::close
            );
        }
        return generateAuthorized(request);
    }

    /** 获得分布式许可后才建立物理流，完成、错误和取消均释放许可并记账。 */
    private Flux<GenerationDelta> generateAuthorized(GenerationRequest request) {
        if (model == null) {
            return Flux.error(new BusinessException(ErrorCode.RAG_503_MODEL_UNAVAILABLE));
        }
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));
        for (ModelGateways.Message message : request.messages()) {
            messages.add(
                switch (message.role()) {
                    case ConversationProtocol.USER -> new UserMessage(message.content());
                    case ConversationProtocol.ASSISTANT -> new AssistantMessage(message.content());
                    default -> throw new IllegalArgumentException("Invalid generation message role");
                }
            );
        }
        String stage = MDC.get(TraceContextKeys.USAGE_STAGE);
        String actualStage = stage == null ? ModelStage.GENERATION.name() : stage;
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().maxTokens(
            Math.min(request.maxOutputTokens(), config.maxOutputTokens())
        );
        // Qwen 的结构化任务直接输出 JSON，避免思考 Token 占用规划及核验的短时限。
        if (
            !ModelStage.GENERATION.name().equals(actualStage) &&
            config.model().startsWith(ProviderProtocol.QWEN_MODEL_PREFIX)
        ) {
            options.extraBody(Map.of(ProviderProtocol.ENABLE_THINKING, false));
        }
        UsageService.Call call = usage == null ? null : usage.begin(actualStage, config.model());
        return Flux.defer(() -> model.stream(new Prompt(messages, options.build())))
            .timeout(config.generationTimeout())
            .map(this::delta)
            .onErrorMap(this::generationFailure)
            .doOnNext(delta -> {
                if (call != null) {
                    call.tokens(delta.inputTokens(), delta.outputTokens());
                }
            })
            .doOnComplete(() -> {
                if (call != null) {
                    call.finish(true, null);
                }
            })
            .doOnError(failure -> {
                if (call != null) {
                    call.finish(false, failure);
                }
            })
            .doOnCancel(() -> {
                if (call != null) {
                    call.finish(false, null);
                }
            });
    }

    /** 将供应方失败转换为脱敏的业务错误。 */
    private Throwable generationFailure(Throwable failure) {
        if (failure instanceof BusinessException) {
            return failure;
        }
        ErrorCode errorCode =
            failure instanceof java.util.concurrent.TimeoutException
                ? ErrorCode.RAG_504_TIMEOUT
                : ErrorCode.RAG_503_MODEL_UNAVAILABLE;
        return new BusinessException(errorCode);
    }

    /** 从流式响应中提取公开文本增量。 */
    private GenerationDelta delta(ChatResponse response) {
        // 只暴露公开回答正文，模型推理和工具元数据不传出网关。
        String text = response.getResult() == null ? "" : response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();
        Long input =
            usage == null || usage.getPromptTokens() == null || usage.getPromptTokens() <= 0
                ? null
                : usage.getPromptTokens().longValue();
        Long output =
            usage == null || usage.getCompletionTokens() == null || usage.getCompletionTokens() <= 0
                ? null
                : usage.getCompletionTokens().longValue();
        return new GenerationDelta(text == null ? "" : text, false, input, output);
    }
}
