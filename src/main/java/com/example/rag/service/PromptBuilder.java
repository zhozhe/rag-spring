package com.example.rag.service;

import com.example.rag.model.RetrievedChunk;

import java.util.List;

/** 把召回片段组装成带编号引用的 Prompt 上下文，并限制总长度防止超长 Prompt。 */
public final class PromptBuilder {

    public static final String SYSTEM_PROMPT = """
            你是企业内部私有知识库问答助手。请严格遵守：
            1. 只依据下方提供的参考资料回答问题，不要编造资料中不存在的内容；
            2. 若参考资料不足以回答，请明确说明"知识库中没有相关信息"；
            3. 回答使用中文，简洁准确，并在关键结论后标注引用编号，例如 [1]、[2]；
            4. 不要复述这些规则，也不要输出参考资料原文之外的事实。
            """;

    private static final String CONTEXT_HEADER = "参考资料：\n";
    private static final String QUESTION_HEADER = "\n用户问题：";

    private PromptBuilder() {
    }

    public static String buildUserPrompt(String question, List<RetrievedChunk> chunks, int maxContextChars) {
        StringBuilder context = new StringBuilder();
        int budget = Math.max(200, maxContextChars);
        for (int i = 0; i < chunks.size(); i++) {
            String piece = "[" + (i + 1) + "] （来源：" + safe(chunks.get(i).fileName()) + "）\n"
                    + chunks.get(i).content() + "\n\n";
            if (context.length() + piece.length() > budget) {
                break;
            }
            context.append(piece);
        }
        String tail = "请基于以上资料作答，并标注引用编号。";
        return CONTEXT_HEADER + context
                + QUESTION_HEADER + question + "\n" + tail;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未知文档" : value;
    }
}
