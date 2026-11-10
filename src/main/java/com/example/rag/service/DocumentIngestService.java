package com.example.rag.service;

import com.example.rag.config.RagProperties;
import com.example.rag.document.DocumentParser;
import com.example.rag.document.TextChunker;
import com.example.rag.model.KnowledgeDocument;
import com.example.rag.repository.DocumentRepository;
import com.example.rag.vectorstore.ChunkStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RAG 入库链路：解析 -> 分块 -> 分批向量化 -> 写入向量库。
 * 针对大文档做分片批处理、整体 deadline 控制、单批向量化超时与失败状态落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private final DocumentParser documentParser;
    private final TextChunker textChunker;
    private final EmbeddingModel embeddingModel;
    private final ChunkStore chunkStore;
    private final DocumentRepository documentRepository;
    private final RagProperties properties;

    private final ExecutorService embeddingExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "rag-embed-worker");
        thread.setDaemon(true);
        return thread;
    });

    @Async("ingestExecutor")
    public void ingestAsync(String documentId, byte[] content, String fileName, String knowledgeBase) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(1, properties.getIngestTimeoutSeconds()));
        try {
            // 幂等：重复上传 / 重试时先清理旧向量
            chunkStore.removeByDocumentId(documentId);

            DocumentParser.ParsedDocument parsed = documentParser.parse(content, fileName);
            List<TextSegment> segments = buildSegments(parsed.text(), documentId, fileName, knowledgeBase);
            if (segments.isEmpty()) {
                throw new IllegalStateException("文档切分后没有有效文本片段");
            }

            int batchSize = Math.max(1, properties.getIngestBatchSize());
            int stored = 0;
            for (int from = 0; from < segments.size(); from += batchSize) {
                int to = Math.min(from + batchSize, segments.size());
                checkDeadline(deadlineNanos);

                List<TextSegment> batch = segments.subList(from, to);
                List<Embedding> embeddings = embedWithTimeout(batch, deadlineNanos);
                List<String> ids = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    ids.add(documentId + ":" + (from + i));
                }
                chunkStore.addAll(ids, embeddings, batch);
                stored += batch.size();
                log.debug("文档 {} 已入库 {}/{} 个片段", documentId, stored, segments.size());
            }

            documentRepository.updateStatus(documentId, KnowledgeDocument.STATUS_READY, stored, null);
            log.info("文档入库完成: id={}, file={}, chunks={}", documentId, fileName, stored);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("文档入库失败: id={}, file={}, reason={}", documentId, fileName, message, e);
            documentRepository.updateStatus(documentId, KnowledgeDocument.STATUS_FAILED, 0,
                    truncate(message, 1800));
        }
    }

    private List<TextSegment> buildSegments(String text, String documentId, String fileName, String knowledgeBase) {
        Metadata base = new Metadata();
        base.put(ChunkStore.DOCUMENT_ID, documentId);
        base.put(ChunkStore.KNOWLEDGE_BASE, knowledgeBase == null ? "default" : knowledgeBase);
        base.put(ChunkStore.FILE_NAME, fileName == null ? "" : fileName);

        List<TextSegment> split = textChunker.split(text, base);
        List<TextSegment> result = new ArrayList<>(split.size());
        for (int i = 0; i < split.size(); i++) {
            TextSegment segment = split.get(i);
            Metadata metadata = (segment.metadata() == null ? new Metadata() : segment.metadata()).copy()
                    .put(ChunkStore.DOCUMENT_ID, documentId)
                    .put(ChunkStore.KNOWLEDGE_BASE, knowledgeBase == null ? "default" : knowledgeBase)
                    .put(ChunkStore.FILE_NAME, fileName == null ? "" : fileName)
                    .put(ChunkStore.CHUNK_INDEX, String.valueOf(i));
            result.add(TextSegment.from(segment.text(), metadata));
        }
        return result;
    }

    private List<Embedding> embedWithTimeout(List<TextSegment> batch, long deadlineNanos)
            throws TimeoutException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        checkDeadline(deadlineNanos);
        Future<List<Embedding>> future = embeddingExecutor.submit(
                () -> embeddingModel.embedAll(batch).content());
        try {
            return future.get(Math.max(1, remaining), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("向量化耗时超过剩余处理时间");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量化被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("向量化失败：" + cause.getMessage(), cause);
        }
    }

    private static void checkDeadline(long deadlineNanos) throws TimeoutException {
        if (System.nanoTime() > deadlineNanos) {
            throw new TimeoutException("文档处理超过 rag.ingest-timeout-seconds 限制的秒数");
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @PreDestroy
    public void shutdown() {
        embeddingExecutor.shutdownNow();
    }
}
