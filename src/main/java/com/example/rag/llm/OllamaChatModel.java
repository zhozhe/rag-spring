package com.example.rag.llm;

import com.example.rag.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对接本地 Ollama 的 /api/chat（非流式），带超时、重试与异常归类。
 */
@Slf4j
public class OllamaChatModel implements ChatModel {

    private final RestClient client;
    private final String model;
    private final double temperature;
    private final int maxRetries;

    public OllamaChatModel(String baseUrl, String model, double temperature,
                           int timeoutSeconds, int maxRetries) {
        this.client = OllamaHttpClient.create(baseUrl, timeoutSeconds);
        this.model = model;
        this.temperature = temperature;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("options", Map.of("temperature", temperature));

        JsonNode response = callWithRetry(body);
        String answer = response.path("message").path("content").asText("");
        if (answer.isBlank()) {
            throw new BusinessException(502, "Ollama 未返回有效回答");
        }
        return answer.trim();
    }

    @Override
    public String modelName() {
        return model;
    }

    private JsonNode callWithRetry(Map<String, Object> body) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                JsonNode node = client.post()
                        .uri("/api/chat")
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
                log.warn("Ollama 调用失败, attempt={}/{}, reason={}", attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt < maxRetries) {
                    sleep(300L * (attempt + 1));
                }
            }
        }
        boolean timeout = last != null && last.getMessage() != null
                && last.getMessage().toLowerCase().contains("timeout");
        throw new BusinessException(timeout ? 504 : 502,
                timeout ? "大模型调用超时，请稍后重试或调大 rag.llm.timeout-seconds"
                        : "大模型调用失败：" + (last == null ? "未知错误" : last.getMessage()),
                last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
