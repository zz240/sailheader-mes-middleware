package com.example.edgegateway.service;

import com.example.edgegateway.config.EdgeMappingProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LangChain4j LLM 客户端：OpenAI 兼容端点（Ollama/vLLM/云厂商网关均可）。
 * 超时在 builder 上配置，调用异常向上抛，由 LlmMappingSuggester 回退规则引擎。
 * ChatModel 懒加载：构造时不创建 HttpClient，避免 LLM 服务/环境不可用时拖垮应用启动
 * （JDK 21 在部分 Windows 环境建 loopback pipe 会抛 SocketException，实测触发）。
 */
@Component
public class LangChain4jLlmClient implements LlmClient {

    private final EdgeMappingProperties properties;
    private volatile ChatModel chatModel;

    public LangChain4jLlmClient(EdgeMappingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return model().chat(systemPrompt + "\n\n" + userPrompt);
    }

    private ChatModel model() {
        ChatModel local = chatModel;
        if (local == null) {
            synchronized (this) {
                local = chatModel;
                if (local == null) {
                    EdgeMappingProperties.Llm llm = properties.getLlm();
                    local = OpenAiChatModel.builder()
                            .baseUrl(llm.getBaseUrl())
                            .modelName(llm.getModel())
                            .apiKey(llm.getApiKey())
                            .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                            .build();
                    chatModel = local;
                }
            }
        }
        return local;
    }
}
