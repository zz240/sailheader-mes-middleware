package com.example.edgegateway.service;

import com.example.edgegateway.config.EdgeMappingProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LangChain4j LLM 客户端：OpenAI 兼容端点（Ollama/vLLM/云厂商网关均可）。
 * 超时在 builder 上配置，调用异常向上抛，由 LlmMappingSuggester 回退规则引擎。
 */
@Component
public class LangChain4jLlmClient implements LlmClient {

    private final ChatModel chatModel;

    public LangChain4jLlmClient(EdgeMappingProperties properties) {
        EdgeMappingProperties.Llm llm = properties.getLlm();
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(llm.getBaseUrl())
                .modelName(llm.getModel())
                .apiKey(llm.getApiKey())
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chatModel.chat(systemPrompt + "\n\n" + userPrompt);
    }
}
