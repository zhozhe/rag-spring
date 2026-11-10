package com.example.rag.model;

/**
 * 向量检索命中的一个文档片段。
 *
 * @param score 相关度分数 [0,1]，由余弦相似度换算：(cos + 1) / 2
 */
public record RetrievedChunk(
        String chunkId,
        String documentId,
        String fileName,
        String knowledgeBase,
        int chunkIndex,
        String content,
        double score
) {
}
