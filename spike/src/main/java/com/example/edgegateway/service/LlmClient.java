package com.example.edgegateway.service;

/** LLM 会话客户端抽象：便于测试替换与超时/失败回退。 */
public interface LlmClient {

    /** 带系统提示的对话，返回模型输出文本；异常向上抛，由调用方回退。 */
    String chat(String systemPrompt, String userPrompt);
}
