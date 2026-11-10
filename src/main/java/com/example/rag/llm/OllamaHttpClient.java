package com.example.rag.llm;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Ollama HTTP 客户端公共构造，统一控制连接/读取超时。 */
public final class OllamaHttpClient {

    private OllamaHttpClient() {
    }

    public static RestClient create(String baseUrl, int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(1, timeoutSeconds) * 1000);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
