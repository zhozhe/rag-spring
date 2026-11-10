package com.example.rag.model;

/**
 * 检索条件：可按知识库、单个文档缩小召回范围。
 */
public record SearchScope(String knowledgeBase, String documentId, int topK, double minScore) {

    public static SearchScope of(String knowledgeBase, String documentId, int topK, double minScore) {
        return new SearchScope(
                normalize(knowledgeBase),
                normalize(documentId),
                Math.max(1, topK),
                Math.min(1.0, Math.max(0.0, minScore)));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
