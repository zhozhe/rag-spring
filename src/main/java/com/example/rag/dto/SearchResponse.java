package com.example.rag.dto;

import java.util.List;

public record SearchResponse(String query, int total, double minScore, List<Hit> hits) {
    public record Hit(
            String chunkId,
            String documentId,
            String fileName,
            int chunkIndex,
            double score,
            String content
    ) {
    }
}
