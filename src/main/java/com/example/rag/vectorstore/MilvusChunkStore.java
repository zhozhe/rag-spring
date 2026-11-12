package com.example.rag.vectorstore;

import com.example.rag.config.RagProperties;
import com.example.rag.model.RetrievedChunk;
import com.example.rag.model.SearchScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.RelevanceScore;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Milvus 向量库实现：显式 Schema + COSINE / AUTOINDEX，
 * 通过 LangChain4j 的 EmbeddingStore 抽象接入 RAG 链路，支持按文档/知识库过滤与删除。
 */
@Slf4j
public class MilvusChunkStore implements ChunkStore {

    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_CONTENT = "content";

    private final MilvusClientV2 client;
    private final String collection;
    private final int dimension;

    public MilvusChunkStore(RagProperties.Vector vectorProperties) {
        this.collection = vectorProperties.getCollection();
        this.dimension = vectorProperties.getDimension();

        RagProperties.Milvus milvus = vectorProperties.getMilvus();

        ConnectConfig.ConnectConfigBuilder config = ConnectConfig.builder()
                .uri(milvus.getUri())
                .connectTimeoutMs(milvus.getConnectTimeoutMs())
                .rpcDeadlineMs(milvus.getRpcDeadlineMs());
        if (hasText(milvus.getToken())) {
            config.token(milvus.getToken());
        }
        if (hasText(milvus.getUsername())) {
            config.username(milvus.getUsername()).password(milvus.getPassword());
        }

        try {
            this.client = new MilvusClientV2(config.build());
            initCollection();
            log.info("Milvus 集合就绪: {}, dimension={}", collection, dimension);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 Milvus 失败，请检查 rag.vector.milvus.uri 与 Milvus 服务状态: "
                    + e.getMessage(), e);
        }
    }

    private void initCollection() {
        Boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(collection)
                .build());
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        List<CreateCollectionReq.FieldSchema> fields = List.of(
                CreateCollectionReq.FieldSchema.builder()
                        .name(FIELD_ID).dataType(DataType.VarChar).maxLength(64)
                        .isPrimaryKey(true).autoID(false).build(),
                CreateCollectionReq.FieldSchema.builder()
                        .name(FIELD_VECTOR).dataType(DataType.FloatVector).dimension(dimension).build(),
                CreateCollectionReq.FieldSchema.builder()
                        .name(FIELD_CONTENT).dataType(DataType.VarChar).maxLength(8192).build(),
                CreateCollectionReq.FieldSchema.builder()
                        .name(DOCUMENT_ID).dataType(DataType.VarChar).maxLength(64).build(),
                CreateCollectionReq.FieldSchema.builder()
                        .name(KNOWLEDGE_BASE).dataType(DataType.VarChar).maxLength(64).build(),
                CreateCollectionReq.FieldSchema.builder()
                        .name(FILE_NAME).dataType(DataType.VarChar).maxLength(255).build(),
                CreateCollectionReq.FieldSchema.builder()
                        .name(CHUNK_INDEX).dataType(DataType.VarChar).maxLength(16).build()
        );

        IndexParam index = IndexParam.builder()
                .fieldName(FIELD_VECTOR)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        CreateCollectionReq request = CreateCollectionReq.builder()
                .collectionName(collection)
                .description("RAG 私有知识库文档片段")
                .collectionSchema(CreateCollectionReq.CollectionSchema.builder()
                        .fieldSchemaList(fields)
                        .build())
                .indexParams(List.of(index))
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .build();
        // SDK 在显式 Schema + indexParams 时会自动创建索引并 load collection
        client.createCollection(request);
    }

    @Override
    public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        addAll(List.of(id), List.of(embedding), Collections.singletonList(null));
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        addAll(List.of(id), List.of(embedding), List.of(embedded));
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = embeddings.stream().map(e -> UUID.randomUUID().toString()).toList();
        List<TextSegment> emptySegments = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
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
        if (ids.isEmpty()) {
            return;
        }
        List<JsonObject> rows = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            TextSegment segment = segments.get(i);
            Metadata metadata = segment == null || segment.metadata() == null
                    ? new Metadata() : segment.metadata();
            JsonObject row = new JsonObject();
            row.addProperty(FIELD_ID, ids.get(i));
            JsonArray vector = new JsonArray();
            for (float value : embeddings.get(i).vector()) {
                vector.add(value);
            }
            row.add(FIELD_VECTOR, vector);
            row.addProperty(FIELD_CONTENT, truncate(segment == null ? "" : segment.text(), 8000));
            row.addProperty(DOCUMENT_ID, safe(metadata.getString(DOCUMENT_ID)));
            row.addProperty(KNOWLEDGE_BASE, safe(metadata.getString(KNOWLEDGE_BASE)));
            row.addProperty(FILE_NAME, safe(metadata.getString(FILE_NAME)));
            row.addProperty(CHUNK_INDEX, safe(metadata.getString(CHUNK_INDEX)));
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(collection).data(rows).build());
    }

    @Override
    public void remove(String id) {
        removeAll(List.of(id));
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        client.delete(DeleteReq.builder()
                .collectionName(collection)
                .ids(new ArrayList<>(ids))
                .build());
    }

    @Override
    public void removeByDocumentId(String documentId) {
        client.delete(DeleteReq.builder()
                .collectionName(collection)
                .filter(eq(DOCUMENT_ID, documentId))
                .build());
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        List<RetrievedChunk> chunks = searchScoped(request.queryEmbedding(),
                SearchScope.of(null, null, request.maxResults(), request.minScore()));
        List<EmbeddingMatch<TextSegment>> matches = chunks.stream()
                .map(chunk -> new EmbeddingMatch<>(chunk.score(), chunk.chunkId(), null,
                        TextSegment.from(chunk.content(), Metadata.from(Map.of(
                                DOCUMENT_ID, safe(chunk.documentId()),
                                FILE_NAME, safe(chunk.fileName()),
                                KNOWLEDGE_BASE, safe(chunk.knowledgeBase()),
                                CHUNK_INDEX, String.valueOf(chunk.chunkIndex()))))))
                .toList();
        return new EmbeddingSearchResult<>(matches);
    }

    @Override
    public List<RetrievedChunk> searchScoped(Embedding query, SearchScope scope) {
        List<String> outputFields = List.of(FIELD_CONTENT, DOCUMENT_ID, KNOWLEDGE_BASE,
                FILE_NAME, CHUNK_INDEX);
        List<BaseVector> vectors = List.of(new FloatVec(query.vector()));

        SearchReq.SearchReqBuilder request = SearchReq.builder()
                .collectionName(collection)
                .annsField(FIELD_VECTOR)
                .data(vectors)
                .topK(scope.topK())
                .metricType(IndexParam.MetricType.COSINE)
                .outputFields(outputFields)
                .consistencyLevel(ConsistencyLevel.STRONG);

        String expression = buildExpression(scope);
        if (expression != null) {
            request.filter(expression);
        }

        SearchResp response = client.search(request.build());
        List<List<SearchResp.SearchResult>> groups = response.getSearchResults();
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> hits = new ArrayList<>();
        for (SearchResp.SearchResult result : groups.get(0)) {
            // Milvus COSINE 返回余弦相似度 [-1,1]，统一换算为相关度 [0,1]
            float cosine = result.getScore() == null ? 0f : result.getScore();
            double score = RelevanceScore.fromCosineSimilarity(cosine);
            if (score < scope.minScore()) {
                continue;
            }
            Map<String, Object> entity = result.getEntity() == null ? Map.of() : result.getEntity();
            hits.add(new RetrievedChunk(
                    String.valueOf(result.getId()),
                    str(entity, DOCUMENT_ID),
                    str(entity, FILE_NAME),
                    str(entity, KNOWLEDGE_BASE),
                    parseInt(str(entity, CHUNK_INDEX)),
                    str(entity, FIELD_CONTENT),
                    score));
            if (hits.size() >= scope.topK()) {
                break;
            }
        }
        return hits;
    }

    private static String buildExpression(SearchScope scope) {
        List<String> parts = new ArrayList<>();
        if (scope.knowledgeBase() != null) {
            parts.add(eq(KNOWLEDGE_BASE, scope.knowledgeBase()));
        }
        if (scope.documentId() != null) {
            parts.add(eq(DOCUMENT_ID, scope.documentId()));
        }
        return parts.isEmpty() ? null : String.join(" and ", parts);
    }

    private static String eq(String field, String value) {
        return field + " == \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String str(Map<String, Object> entity, String key) {
        Object value = entity.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
//connectconfigbuilder->connectconfig->MilvusClientV2->initCollection()去创建集合
//创建集合中:List整合CreateCollectionReq.FieldSchema类型数据有包括id,vector,content,docid,knowl,name,index->
// CollectionSchemaBuilder类型fields->CollectionSchema对象->CreateCollectionReqBuilder->CreateCollectionReq
