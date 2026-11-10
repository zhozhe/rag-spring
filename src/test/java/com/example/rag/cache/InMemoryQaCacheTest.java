package com.example.rag.cache;

import com.example.rag.dto.AskResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryQaCacheTest {

    private static final String PREFIX = "rag:qa:";

    private static AskResponse response() {
        return new AskResponse("问题", "答案", false, "model", 1, List.of());
    }

    @Test
    void storesAndExpiresAfterTtl() throws InterruptedException {
        InMemoryQaCache cache = new InMemoryQaCache(Duration.ofMillis(40), PREFIX);
        String key = PREFIX + "default:abc";
        cache.put(key, response());

        assertThat(cache.get(key)).isPresent();
        Thread.sleep(80);
        assertThat(cache.get(key)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void invalidatesOnlyTargetKnowledgeBase() {
        InMemoryQaCache cache = new InMemoryQaCache(Duration.ofMinutes(10), PREFIX);
        cache.put(PREFIX + "hr:1", response());
        cache.put(PREFIX + "finance:1", response());

        cache.invalidateKnowledgeBase("hr");

        assertThat(cache.get(PREFIX + "hr:1")).isEmpty();
        assertThat(cache.get(PREFIX + "finance:1")).isPresent();
    }

    @Test
    void cacheKeyChangesWithRetrievalParameters() {
        String a = QaCacheKeys.build(PREFIX, "default", null, 5, 0.65, "model", "年假流程");
        String b = QaCacheKeys.build(PREFIX, "default", null, 3, 0.65, "model", "年假流程");
        String c = QaCacheKeys.build(PREFIX, "default", null, 5, 0.65, "model", "报销流程");

        assertThat(a).startsWith(PREFIX + "default:");
        assertThat(a).isNotEqualTo(b).isNotEqualTo(c);
        assertThat(QaCacheKeys.build(PREFIX, "default", null, 5, 0.65, "model", "年假流程"))
                .isEqualTo(a);
    }
}
