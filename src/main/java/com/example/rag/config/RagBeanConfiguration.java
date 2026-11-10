package com.example.rag.config;

import com.example.rag.cache.InMemoryQaCache;
import com.example.rag.cache.QaCache;
import com.example.rag.cache.RedisQaCache;
import com.example.rag.embedding.HashingEmbeddingModel;
import com.example.rag.embedding.OllamaEmbeddingModel;
import com.example.rag.llm.ChatModel;
import com.example.rag.llm.MockChatModel;
import com.example.rag.llm.OllamaChatModel;
import com.example.rag.vectorstore.ChunkStore;
import com.example.rag.vectorstore.InMemoryChunkStore;
import com.example.rag.vectorstore.MilvusChunkStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 通过配置切换「本地内存实现」与「真实中间件实现」，
 * 使得不启动 Docker 也能跑通完整 RAG 链路与单元测试。
 */
@Configuration
public class RagBeanConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rag.vector", name = "store", havingValue = "milvus")
    public ChunkStore milvusChunkStore(RagProperties properties) {
        return new MilvusChunkStore(properties.getVector());
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.vector", name = "store", havingValue = "memory", matchIfMissing = true)
    public ChunkStore inMemoryChunkStore() {
        return new InMemoryChunkStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.embedding", name = "provider", havingValue = "ollama")
    public EmbeddingModel ollamaEmbeddingModel(RagProperties properties) {
        RagProperties.Embedding e = properties.getEmbedding();
        return new OllamaEmbeddingModel(e.getBaseUrl(), e.getModel(), e.getDimension(),
                e.getTimeoutSeconds(), e.getMaxRetries());
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.embedding", name = "provider", havingValue = "hashing", matchIfMissing = true)
    public EmbeddingModel hashingEmbeddingModel(RagProperties properties) {
        return new HashingEmbeddingModel(properties.getEmbedding().getDimension());
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.llm", name = "provider", havingValue = "ollama")
    public ChatModel ollamaChatModel(RagProperties properties) {
        RagProperties.Llm llm = properties.getLlm();
        return new OllamaChatModel(llm.getBaseUrl(), llm.getModel(), llm.getTemperature(),
                llm.getTimeoutSeconds(), llm.getMaxRetries());
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.llm", name = "provider", havingValue = "mock", matchIfMissing = true)
    public ChatModel mockChatModel(RagProperties properties) {
        return new MockChatModel(properties.getLlm().getModel());
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.cache", name = "type", havingValue = "redis")
    public QaCache redisQaCache(StringRedisTemplate redis, ObjectMapper objectMapper, RagProperties properties) {
        RagProperties.Cache cache = properties.getCache();
        return new RedisQaCache(redis, objectMapper, cache.getTtlMinutes(), cache.getKeyPrefix());
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.cache", name = "type", havingValue = "memory", matchIfMissing = true)
    public QaCache inMemoryQaCache(RagProperties properties) {
        RagProperties.Cache cache = properties.getCache();
        return new InMemoryQaCache(cache.getTtlMinutes(), cache.getKeyPrefix());
    }
}
