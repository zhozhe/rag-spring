package com.example.rag.cache;

import com.example.rag.dto.AskResponse;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 带 TTL 的本地缓存，用于 dev / 单元测试。 */
public class InMemoryQaCache implements QaCache {

    private record Entry(AskResponse response, long expireAt) {
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final String keyPrefix;

    public InMemoryQaCache(long ttlMinutes, String keyPrefix) {
        this(Duration.ofMinutes(Math.max(1, ttlMinutes)), keyPrefix);
    }

    /** 供单元测试精确控制过期时间。 */
    public InMemoryQaCache(Duration ttl, String keyPrefix) {
        this.ttlMillis = Math.max(1, ttl.toMillis());
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<AskResponse> get(String key) {
        purgeExpired();
        Entry entry = store.get(key);
        if (entry == null || entry.expireAt() < System.currentTimeMillis()) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    @Override
    public void put(String key, AskResponse response) {
        store.put(key, new Entry(response, System.currentTimeMillis() + ttlMillis));
    }

    @Override
    public void invalidateKnowledgeBase(String knowledgeBase) {
        String prefix = keyPrefix + safe(knowledgeBase) + ":";
        store.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue().expireAt() < now);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    public int size() {
        purgeExpired();
        return store.size();
    }
}
