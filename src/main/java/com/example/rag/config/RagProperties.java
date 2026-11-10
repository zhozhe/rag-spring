package com.example.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 所有 RAG 可调参数集中在此，便于针对召回效果做调优（chunk 大小、重叠、阈值、TopK 等）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Storage storage = new Storage();

    /** 单个片段最大字符数 */
    private int chunkSize = 500;
    /** 相邻片段重叠字符数 */
    private int chunkOverlap = 60;
    /** 小于该长度的片段直接丢弃 */
    private int minChunkChars = 12;
    /** 单个文档解析后允许的最大文本长度 */
    private int maxTextChars = 500000;
    private int maxFileSizeMb = 20;

    /** 大文档分片：每批向量化的片段数 */
    private int ingestBatchSize = 32;
    /** 单个文档整体入库超时（秒） */
    private int ingestTimeoutSeconds = 300;

    private int retrievalTopK = 5;
    /** 相关度分数阈值，取值 [0,1]，由余弦相似度换算而来 */
    private double similarityThreshold = 0.65;
    /** 送入大模型的上下文最大字符数，防止超长 Prompt */
    private int maxContextChars = 6000;

    private Vector vector = new Vector();
    private Embedding embedding = new Embedding();
    private Llm llm = new Llm();
    private Cache cache = new Cache();

    @Data
    public static class Storage {
        private String dir = "./data/uploads";
    }

    @Data
    public static class Vector {
        /** memory | milvus */
        private String store = "memory";
        private String collection = "rag_chunks";
        private int dimension = 384;
        private Milvus milvus = new Milvus();
    }

    @Data
    public static class Milvus {
        private String uri = "http://localhost:19530";
        private String token = "";
        private String username = "";
        private String password = "";
        private long connectTimeoutMs = 10000;
        private long rpcDeadlineMs = 30000;
    }

    @Data
    public static class Embedding {
        /** hashing（本地测试） | ollama */
        private String provider = "hashing";
        private String model = "nomic-embed-text";
        private int dimension = 384;
        private String baseUrl = "http://localhost:11434";
        private int timeoutSeconds = 60;
        private int maxRetries = 2;
    }

    @Data
    public static class Llm {
        /** mock（本地测试） | ollama */
        private String provider = "mock";
        private String model = "qwen2.5:7b";
        private String baseUrl = "http://localhost:11434";
        private double temperature = 0.1;
        private int timeoutSeconds = 120;
        private int maxRetries = 2;
        /** 无召回时是否仍调用大模型 */
        private boolean answerWithoutContext = false;
    }

    @Data
    public static class Cache {
        private boolean enabled = true;
        /** memory | redis */
        private String type = "memory";
        private long ttlMinutes = 60;
        private String keyPrefix = "rag:qa:";
    }
}
