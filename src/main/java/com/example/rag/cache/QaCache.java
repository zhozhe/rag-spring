package com.example.rag.cache;

import com.example.rag.dto.AskResponse;

import java.util.Optional;

/** 高频问答缓存抽象，屏蔽 Redis 与本地内存实现差异。 */
public interface QaCache {

    Optional<AskResponse> get(String key);

    void put(String key, AskResponse response);

    /** 文档变更后按知识库失效，避免继续返回过期答案。 */
    void invalidateKnowledgeBase(String knowledgeBase);
}
