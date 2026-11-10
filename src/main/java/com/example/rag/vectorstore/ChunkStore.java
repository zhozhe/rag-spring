package com.example.rag.vectorstore;

import com.example.rag.model.RetrievedChunk;
import com.example.rag.model.SearchScope;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * 在 LangChain4j {@link EmbeddingStore} 标准抽象之上扩展业务能力：
 * 按知识库/文档限定检索范围、按文档删除全部向量。
 */
public interface ChunkStore extends EmbeddingStore<TextSegment> {

    String DOCUMENT_ID = "document_id";
    String KNOWLEDGE_BASE = "knowledge_base";
    String FILE_NAME = "file_name";
    String CHUNK_INDEX = "chunk_index";

    List<RetrievedChunk> searchScoped(Embedding query, SearchScope scope);

    void removeByDocumentId(String documentId);
}
