package com.example.edgegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 映射智能化配置：建议器类型（rule|llm，站点级可在 site.suggester 覆盖）与 LLM 端点。
 */
@Component
@ConfigurationProperties(prefix = "edge.mapping")
public class EdgeMappingProperties {

    private String suggester = "rule";

    private final Llm llm = new Llm();

    public String getSuggester() {
        return suggester;
    }

    public void setSuggester(String suggester) {
        this.suggester = suggester;
    }

    public Llm getLlm() {
        return llm;
    }

    public static class Llm {

        private String baseUrl = "http://localhost:11434/v1";
        private String model = "qwen2.5:7b";
        private String apiKey = "not-needed";
        private int timeoutSeconds = 30;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
