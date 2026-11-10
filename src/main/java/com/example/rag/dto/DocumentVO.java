package com.example.rag.dto;

import com.example.rag.model.KnowledgeDocument;

public record DocumentVO(
        String id,
        String fileName,
        String knowledgeBase,
        String contentType,
        long fileSize,
        int chunkCount,
        String status,
        String errorMessage,
        String createdAt,
        String updatedAt
) {
    public static DocumentVO from(KnowledgeDocument doc) {
        return new DocumentVO(
                doc.getId(), doc.getFileName(), doc.getKnowledgeBase(), doc.getContentType(),
                doc.getFileSize(), doc.getChunkCount(), doc.getStatus(), doc.getErrorMessage(),
                doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString(),
                doc.getUpdatedAt() == null ? null : doc.getUpdatedAt().toString());
    }
}
