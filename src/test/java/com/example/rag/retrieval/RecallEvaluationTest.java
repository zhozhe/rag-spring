package com.example.rag.retrieval;

import com.example.rag.config.RagProperties;
import com.example.rag.document.TextChunker;
import com.example.rag.embedding.HashingEmbeddingModel;
import com.example.rag.model.RetrievedChunk;
import com.example.rag.model.SearchScope;
import com.example.rag.vectorstore.ChunkStore;
import com.example.rag.vectorstore.InMemoryChunkStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 召回率评估：用一个可控的小型知识库，网格对比不同 chunk 大小与相似度阈值下的 Recall@K，
 * 验证「减小 chunk + 放宽阈值」能提升问答检索效果，并给出推荐参数。
 */
class RecallEvaluationTest {

    private static final List<String> TOPICS = List.of(
            "员工年假申请流程：员工需在内部系统提交年假申请，提前三个工作日提交，由直属主管审批，最后由人力资源部备案归档。",
            "差旅费用报销标准：出差结束后七个工作日内提交发票与行程单，由财务部门审核票据，超额部分需部门总监批准。",
            "办公设备领用规范：新员工领用笔记本电脑需在资产系统登记，联系IT服务台领取，离职时须完整归还设备。",
            "门禁与访客管理：员工门禁卡由前台统一发放，外来访客需前台登记身份并由接待人陪同，安全部门定期核查权限。",
            "远程办公申请：远程办公需提前在考勤系统申请并经主管审批，通过VPN接入内网，考勤记录以系统打卡为准。",
            "外部培训报销：参加外部培训课程需先在学习平台备案，结业后凭课程证书与学费发票办理报销手续。",
            "合同用印流程：合同文本须经法务部门审核，用印前填写用印登记单，由公章管理员现场盖章并留存扫描件。",
            "数据备份策略：业务数据每日执行增量备份，每周全量备份并保存异地容灾副本，每季度组织一次恢复演练。"
    );

    private static final List<String> QUESTIONS = List.of(
            "年假申请要提前几个工作日提交，由谁审批",
            "差旅报销发票在几天内提交，哪个部门审核",
            "新员工领用笔记本电脑要去哪里登记和领取",
            "外来访客门禁卡如何登记由谁陪同",
            "远程办公如何申请，通过什么接入内网",
            "外部培训凭课程证书和学费发票如何报销",
            "合同用印前需要哪个部门审核和登记",
            "业务数据增量备份与异地容灾恢复演练怎么做"
    );

    private static final String KB = "eval-kb";

    private record Config(int chunkSize, int overlap, double threshold, int topK) {
    }

    private record Metrics(double recall, double precision, Config config) {
    }

    @Test
    void tuningChunkSizeAndThresholdImprovesRecall() {
        Config baseline = new Config(1200, 0, 0.88, 3);
        Config tuned = new Config(220, 30, 0.55, 5);

        Metrics base = evaluate(baseline);
        Metrics best = evaluate(tuned);

        assertThat(best.recall()).isGreaterThanOrEqualTo(0.75);
        assertThat(best.recall()).isGreaterThanOrEqualTo(base.recall());
    }

    @Test
    void overlyHighThresholdDropsRecall() {
        double low = evaluate(new Config(220, 30, 0.55, 5)).recall();
        double high = evaluate(new Config(220, 30, 0.98, 5)).recall();
        assertThat(high).isLessThanOrEqualTo(low);
    }

    @Test
    void printTuningMatrix() {
        System.out.println("\n===== 召回率调优矩阵 (Recall@K / Precision) =====");
        List<Metrics> all = new ArrayList<>();
        for (int chunkSize : List.of(180, 220, 400, 800)) {
            for (double threshold : List.of(0.55, 0.65, 0.75)) {
                Metrics metrics = evaluate(new Config(chunkSize, 30, threshold, 5));
                all.add(metrics);
                System.out.printf("chunk=%4d  threshold=%.2f  recall=%.3f  precision=%.3f%n",
                        chunkSize, threshold, metrics.recall(), metrics.precision());
            }
        }
        Metrics best = all.stream()
                .max(Comparator.comparingDouble(Metrics::recall).thenComparingDouble(Metrics::precision))
                .orElseThrow();
        System.out.printf("推荐参数: chunk=%d, threshold=%.2f, topK=%d, recall=%.3f%n",
                best.config().chunkSize(), best.config().threshold(), best.config().topK(), best.recall());
        System.out.println("=================================================\n");
        assertThat(best.recall()).isGreaterThanOrEqualTo(0.75);
    }

    private Metrics evaluate(Config config) {
        RagProperties properties = new RagProperties();
        properties.setMinChunkChars(5);
        TextChunker chunker = new TextChunker(properties);
        HashingEmbeddingModel model = new HashingEmbeddingModel(384);
        InMemoryChunkStore store = buildCorpus(chunker, model, config);

        int hits = 0;
        double precisionSum = 0.0;
        for (int i = 0; i < QUESTIONS.size(); i++) {
            String expectedDocId = "doc-" + i;
            Embedding query = model.embed(QUESTIONS.get(i)).content();
            List<RetrievedChunk> results = store.searchScoped(query,
                    SearchScope.of(KB, null, config.topK(), config.threshold()));
            long relevant = results.stream()
                    .filter(hit -> expectedDocId.equals(hit.documentId()))
                    .count();
            if (relevant > 0) {
                hits++;
            }
            precisionSum += (double) relevant / config.topK();
        }
        return new Metrics((double) hits / QUESTIONS.size(),
                precisionSum / QUESTIONS.size(), config);
    }

    private static InMemoryChunkStore buildCorpus(TextChunker chunker,
                                                  HashingEmbeddingModel model,
                                                  Config config) {
        InMemoryChunkStore store = new InMemoryChunkStore();
        for (int i = 0; i < TOPICS.size(); i++) {
            String documentId = "doc-" + i;
            StringBuilder text = new StringBuilder(TOPICS.get(i)).append("\n\n");
            for (int paragraph = 1; paragraph <= 3; paragraph++) {
                text.append(filler(i, paragraph)).append("\n\n");
            }
            Metadata base = new Metadata()
                    .put(ChunkStore.DOCUMENT_ID, documentId)
                    .put(ChunkStore.KNOWLEDGE_BASE, KB)
                    .put(ChunkStore.FILE_NAME, "topic-" + i + ".md");

            List<TextSegment> segments = chunker.split(text.toString(), base,
                    config.chunkSize(), config.overlap());
            for (int j = 0; j < segments.size(); j++) {
                TextSegment segment = segments.get(j);
                Metadata metadata = segment.metadata().copy()
                        .put(ChunkStore.CHUNK_INDEX, String.valueOf(j));
                TextSegment enriched = TextSegment.from(segment.text(), metadata);
                store.add(documentId + ":" + j, model.embed(enriched).content(), enriched);
            }
        }
        return store;
    }

    private static String filler(int docIndex, int paragraph) {
        return "第" + paragraph + "节补充说明：本规范由企业知识库统一发布并归档，"
                + "修订编号 KB-" + docIndex + "-" + paragraph + "，适用于各职能部门的日常协作场景，"
                + "执行过程中如遇系统异常请记录工单编号并联系知识库管理员，"
                + "文档内容按季度复核，历史版本归档保存，查阅权限按岗位层级分配，"
                + "相关表格模板可在内部门户下载，填写后按流程提交归档。";
    }
}
