package com.example.rag.cache;

import com.example.rag.dto.AskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Redis 缓存高频问答结果，减少向量库与大模型调用压力。 */
@Slf4j
public class RedisQaCache implements QaCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final String keyPrefix;

    public RedisQaCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                        long ttlMinutes, String keyPrefix) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<AskResponse> get(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, AskResponse.class));
        } catch (Exception e) {
            // 缓存异常不应阻断主链路
            log.warn("读取问答缓存失败, key={}, reason={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, AskResponse response) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(response), ttl);
        } catch (Exception e) {
            log.warn("写入问答缓存失败, key={}, reason={}", key, e.getMessage());
        }
    }

    @Override
    public void invalidateKnowledgeBase(String knowledgeBase) {
        String kb = knowledgeBase == null || knowledgeBase.isBlank() ? "default" : knowledgeBase.trim();
        try {
            Set<String> keys = redis.keys(keyPrefix + kb + ":*");
//            rag:kb:kb001:chunk:1     知识库id,chunk id
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("清理问答缓存失败, kb={}, reason={}", kb, e.getMessage());
        }
    }
}
