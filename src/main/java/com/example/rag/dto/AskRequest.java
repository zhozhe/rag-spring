package com.example.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题长度不能超过 2000 字符")
        String question,

        String knowledgeBase,
        String documentId,
        Integer topK,
        Double minScore
) {
}
