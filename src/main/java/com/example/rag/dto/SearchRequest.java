package com.example.rag.dto;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank(message = "检索内容不能为空") String query,
        String knowledgeBase,
        String documentId,
        Integer topK,
        Double minScore
) {
}
