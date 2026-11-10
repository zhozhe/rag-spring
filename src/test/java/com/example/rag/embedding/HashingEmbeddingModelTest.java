package com.example.rag.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(384);

    @Test
    void producesDeterministicNormalizedVectors() {
        float[] first = model.vectorOf("年假申请流程与审批权限");
        float[] second = model.vectorOf("年假申请流程与审批权限");

        assertThat(first).hasSize(384);
        assertThat(first).containsExactly(second);
        assertThat(norm(first)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(model.dimension()).isEqualTo(384);
    }

    @Test
    void embedAllKeepsInputOrder() {
        List<Embedding> embeddings = model.embedAll(List.of(
                TextSegment.from("年假"), TextSegment.from("报销"), TextSegment.from("门禁"))).content();

        assertThat(embeddings).hasSize(3);
        assertThat(embeddings.get(0).vector()).containsExactly(model.vectorOf("年假"));
    }

    @Test
    void relatedTextIsMoreSimilarThanUnrelatedText() {
        float[] query = model.vectorOf("年假申请流程如何审批");
        float[] related = model.vectorOf("员工年假申请流程由直属主管审批，人力资源部备案");
        float[] unrelated = model.vectorOf("数据库集群监控告警阈值与异地容灾备份策略");

        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }

    static double norm(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot / (norm(a) * norm(b));
    }
}
