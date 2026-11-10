package com.example.rag.service;

import com.example.rag.common.BusinessException;
import com.example.rag.config.RagProperties;
import com.example.rag.model.RetrievedChunk;
import com.example.rag.model.SearchScope;
import com.example.rag.vectorstore.ChunkStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 向量相似度检索：问题向量化 -> 向量库 TopK 召回 -> 阈值过滤。 */
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingModel embeddingModel;
    private final ChunkStore chunkStore;
    private final RagProperties properties;

    public List<RetrievedChunk> retrieve(String query, String knowledgeBase, String documentId,
                                         Integer topK, Double minScore) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索内容不能为空");
        }
        int k = topK == null || topK <= 0 ? properties.getRetrievalTopK() : topK;
        double threshold = minScore == null ? properties.getSimilarityThreshold() : minScore;
        if (threshold < 0 || threshold > 1) {
            throw new BusinessException("minScore 必须在 [0,1] 之间");
        }
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        return chunkStore.searchScoped(queryEmbedding, SearchScope.of(knowledgeBase, documentId, k, threshold));
    }

    public int effectiveTopK(Integer topK) {
        return topK == null || topK <= 0 ? properties.getRetrievalTopK() : topK;
    }

    public double effectiveThreshold(Double minScore) {
        return minScore == null ? properties.getSimilarityThreshold() : minScore;
    }
}
