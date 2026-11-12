package com.example.rag.embedding;

import com.example.rag.common.BusinessException;
import com.example.rag.llm.OllamaHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对接 Ollama 本地向量模型（如 nomic-embed-text）。
 * 优先使用批量接口 /api/embed；旧版本 Ollama 自动回退到单条 /api/embeddings。
 */
@Slf4j
public class OllamaEmbeddingModel implements EmbeddingModel {

    private final RestClient client;
    private final String model;
    private final int dimension;
    private final int maxRetries;

    public OllamaEmbeddingModel(String baseUrl, String model, int dimension, int timeoutSeconds, int maxRetries) {
        this.client = OllamaHttpClient.create(baseUrl, timeoutSeconds);
        this.model = model;
        this.dimension = dimension;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        List<String> inputs = segments.stream().map(TextSegment::text).toList();
        try {
            return Response.from(embedBatch(inputs));
        } catch (Exception e) {
            log.warn("Ollama 批量向量化失败，回退单条接口: {}", e.getMessage());
            List<Embedding> single = new ArrayList<>(inputs.size());
            for (String input : inputs) {
                single.add(Embedding.from(embedSingle(input)));
            }
            return Response.from(single);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private List<Embedding> embedBatch(List<String> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("truncate", true);

        JsonNode root = callWithRetry("/api/embed", body);
        JsonNode arrays = root.path("embeddings");
        if (!arrays.isArray() || arrays.size() != inputs.size()) {
            throw new IllegalStateException("向量化返回数量不匹配: " + arrays.size() + " / " + inputs.size());
        }
        List<Embedding> result = new ArrayList<>(inputs.size());
        for (JsonNode array : arrays) {
            result.add(Embedding.from(readVector(array)));
        }
        return result;
    }

    private float[] embedSingle(String input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", input);
        JsonNode root = callWithRetry("/api/embeddings", body);
        return readVector(root.path("embedding"));
    }

    private float[] readVector(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalStateException("Ollama 未返回有效向量");
        }
        float[] vector = new float[array.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) array.get(i).asDouble();
        }
        if (vector.length != dimension) {
            throw new BusinessException(500,
                    "向量维度不匹配: 模型返回 " + vector.length + "，配置 rag.embedding.dimension=" + dimension);
        }
        return vector;
    }

    private JsonNode callWithRetry(String path, Map<String, Object> body) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                JsonNode node = client.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                if (node == null) {
                    throw new IllegalStateException("Ollama 返回空响应");
                }
                return node;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxRetries) {
                    sleep(200L * (attempt + 1));
                }
            }
        }
        throw new BusinessException(502, "Ollama 向量化调用失败：" + last.getMessage(), last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
//批量向量化,利用ollama的api/embed,对textsegment抓出text组成body调用client.post得到的node,取出当中embedding数据作为Response
//如果批量向量化出错,对每条text调用api/embeddings向量化组成response
