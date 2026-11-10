package com.example.rag.vectorstore;

import com.example.rag.model.RetrievedChunk;
import com.example.rag.model.SearchScope;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.RelevanceScore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量库：本地开发与单元测试使用，暴力计算余弦相似度，语义与 Milvus COSINE 保持一致。
 */
public class InMemoryChunkStore implements ChunkStore {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(String id, Embedding embedding, TextSegment segment) {
    }

    private record Scored(Entry entry, double score) {
    }

    @Override
    public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        entries.put(id, new Entry(id, embedding, null));
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry(id, embedding, embedded));
        return id;
    }

    public void add(String id, Embedding embedding, TextSegment segment) {
        entries.put(id, new Entry(id, embedding, segment));
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = new ArrayList<>(embeddings.size());
        List<TextSegment> emptySegments = new ArrayList<>(embeddings.size());
        for (Embedding ignored : embeddings) {
            ids.add(UUID.randomUUID().toString());
            emptySegments.add(null);
        }
        addAll(ids, embeddings, emptySegments);
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        if (ids.size() != embeddings.size() || ids.size() != segments.size()) {
            throw new IllegalArgumentException("ids / embeddings / segments 数量必须一致");
        }
        for (int i = 0; i < ids.size(); i++) {
            entries.put(ids.get(i), new Entry(ids.get(i), embeddings.get(i), segments.get(i)));
        }
    }

    @Override
    public void remove(String id) {
        entries.remove(id);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        ids.forEach(entries::remove);
    }

    @Override
    public void removeAll() {
        entries.clear();
    }

    @Override
    public void removeByDocumentId(String documentId) {
        entries.values().removeIf(entry -> documentId.equals(metadataOf(entry).getString(DOCUMENT_ID)));
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (Scored scored : rank(request.queryEmbedding(), null,
                request.maxResults(), request.minScore())) {
            matches.add(new EmbeddingMatch<>(scored.score(), scored.entry().id(),
                    scored.entry().embedding(), scored.entry().segment()));
        }
        return new EmbeddingSearchResult<>(matches);
    }

    @Override
    public List<RetrievedChunk> searchScoped(Embedding query, SearchScope scope) {
        List<RetrievedChunk> hits = new ArrayList<>();
        for (Scored scored : rank(query, scope, scope.topK(), scope.minScore())) {
            Entry entry = scored.entry();
            TextSegment segment = entry.segment();
            Metadata metadata = metadataOf(entry);
            hits.add(new RetrievedChunk(
                    entry.id(),
                    metadata.getString(DOCUMENT_ID),
                    metadata.getString(FILE_NAME),
                    metadata.getString(KNOWLEDGE_BASE),
                    parseInt(metadata.getString(CHUNK_INDEX)),
                    segment == null ? "" : segment.text(),
                    scored.score()));
        }
        return hits;
    }

    private List<Scored> rank(Embedding query, SearchScope scope, int topK, double minScore) {
        return entries.values().stream()
                .filter(entry -> inScope(entry, scope))
                .map(entry -> new Scored(entry,
                        RelevanceScore.fromCosineSimilarity(cosine(query.vector(), entry.embedding().vector()))))
                .filter(scored -> scored.score() >= minScore)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .toList();
    }

    private boolean inScope(Entry entry, SearchScope scope) {
        if (scope == null) {
            return true;
        }
        Metadata metadata = metadataOf(entry);
        if (scope.knowledgeBase() != null
                && !scope.knowledgeBase().equals(metadata.getString(KNOWLEDGE_BASE))) {
            return false;
        }
        return scope.documentId() == null
                || scope.documentId().equals(metadata.getString(DOCUMENT_ID));
    }

    private static Metadata metadataOf(Entry entry) {
        return entry.segment() == null || entry.segment().metadata() == null
                ? new Metadata()
                : entry.segment().metadata();
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int size() {
        return entries.size();
    }

    /** 供调试：列出当前元数据（避免直接暴露内部 Map）。 */
    public Map<String, Object> describe(String chunkId) {
        Entry entry = entries.get(chunkId);
        return entry == null ? Map.of() : metadataOf(entry).toMap();
    }

    private static double cosine(float[] a, float[] b) {
        int length = Math.min(a.length, b.length);
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
