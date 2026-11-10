package com.example.rag.llm;

/**
 * 本地离线生成模型：把检索到的参考资料摘录成答案，
 * 用于在没有 Ollama 的环境里跑通完整问答链路并做单元测试。
 */
public class MockChatModel implements ChatModel {

    private static final String CONTEXT_MARKER = "参考资料：";
    private static final String QUESTION_MARKER = "用户问题：";

    private final String model;

    public MockChatModel(String model) {
        this.model = model;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        int start = userPrompt.indexOf(CONTEXT_MARKER);
        int end = userPrompt.indexOf(QUESTION_MARKER);
        if (start < 0 || end <= start) {
            return "【本地模拟模型】未获取到可用参考资料。";
        }
        String context = userPrompt.substring(start + CONTEXT_MARKER.length(), end).trim();
        if (context.isEmpty()) {
            return "【本地模拟模型】未获取到可用参考资料。";
        }
        String excerpt = context.length() > 300 ? context.substring(0, 300) + "..." : context;
        return "【本地模拟模型】根据知识库资料回答：\n" + excerpt;
    }

    @Override
    public String modelName() {
        return model + "-mock";
    }
}
