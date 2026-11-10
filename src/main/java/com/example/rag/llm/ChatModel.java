package com.example.rag.llm;

/** 大模型调用抽象，屏蔽具体厂商（当前实现为本地 Ollama）。 */
public interface ChatModel {

    String chat(String systemPrompt, String userPrompt);

    String modelName();
}
