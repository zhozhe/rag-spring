package com.example.rag.vectorstore;

import com.example.rag.embedding.HashingEmbeddingModel;
import com.example.rag.model.RetrievedChunk;
import com.example.rag.model.SearchScope;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryChunkStoreTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(384);
    private InMemoryChunkStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryChunkStore();
        store.add("d1:0", model.embed("年假申请需要提前三个工作日提交给直属主管").content(),
                segment("d1", "default", 0, "年假申请需要提前三个工作日提交给直属主管"));
        store.add("d1:1", model.embed("人力资源部负责年假余额备案与归档").content(),
                segment("d1", "default", 1, "人力资源部负责年假余额备案与归档"));
        store.add("d2:0", model.embed("差旅报销需要提交发票由财务部门审核").content(),
                segment("d2", "other-kb", 0, "差旅报销需要提交发票由财务部门审核"));
    }

    @Test
    void respectsTopKAndMinScore() {
        Embedding query = model.embed("年假申请流程").content();

        List<RetrievedChunk> hits = store.searchScoped(query, SearchScope.of(null, null, 2, 0.0));
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).score()).isGreaterThanOrEqualTo(hits.get(1).score());

        List<RetrievedChunk> noHits = store.searchScoped(query, SearchScope.of(null, null, 5, 0.999));
        assertThat(noHits).isEmpty();
    }

    @Test
    void filtersByKnowledgeBaseAndDocument() {
        Embedding query = model.embed("年假申请流程").content();

        assertThat(store.searchScoped(query, SearchScope.of("default", null, 5, 0.0)))
                .allSatisfy(hit -> assertThat(hit.knowledgeBase()).isEqualTo("default"));
        assertThat(store.searchScoped(query, SearchScope.of(null, "d2", 5, 0.0)))
                .allSatisfy(hit -> assertThat(hit.documentId()).isEqualTo("d2"));
    }

    @Test
    void removesAllChunksOfOneDocument() {
        assertThat(store.size()).isEqualTo(3);
        store.removeByDocumentId("d1");
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.searchScoped(model.embed("年假申请流程").content(),
                SearchScope.of(null, null, 5, 0.0)))
                .extracting(RetrievedChunk::documentId)
                .containsExactly("d2");
    }

    @Test
    void implementsLangChain4jEmbeddingStoreContract() {
        var result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(model.embed("年假申请流程").content())
                .maxResults(3)
                .minScore(0.0)
                .build());

        assertThat(result.matches()).hasSize(3);
        assertThat(result.matches().get(0).embedded()).isNotNull();
    }

    private static TextSegment segment(String documentId, String kb, int index, String text) {
        return TextSegment.from(text, new Metadata()
                .put(ChunkStore.DOCUMENT_ID, documentId)
                .put(ChunkStore.KNOWLEDGE_BASE, kb)
                .put(ChunkStore.FILE_NAME, documentId + ".md")
                .put(ChunkStore.CHUNK_INDEX, String.valueOf(index)));
    }
}
