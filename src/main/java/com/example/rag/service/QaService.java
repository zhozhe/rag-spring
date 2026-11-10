package com.example.rag.service;

import com.example.rag.cache.QaCache;
import com.example.rag.cache.QaCacheKeys;
import com.example.rag.config.RagProperties;
import com.example.rag.dto.AskRequest;
import com.example.rag.dto.AskResponse;
import com.example.rag.llm.ChatModel;
import com.example.rag.model.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 知识库问答：查询缓存 -> 检索召回 -> 组装 Prompt 上下文 -> 调用本地大模型 -> 回写缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaService {

    private static final String NO_CONTEXT_ANSWER =
            "知识库中没有检索到与该问题相关的内容，无法作答。请补充相关文档，或降低相似度阈值后重试。";

    private final RetrievalService retrievalService;
    private final ChatModel chatModel;
    private final QaCache qaCache;
    private final RagProperties properties;

    public AskResponse ask(AskRequest request) {
        long start = System.currentTimeMillis();
        int topK = retrievalService.effectiveTopK(request.topK());
        double threshold = retrievalService.effectiveThreshold(request.minScore());
        String knowledgeBase = normalize(request.knowledgeBase());

        String cacheKey = QaCacheKeys.build(
                properties.getCache().getKeyPrefix(), knowledgeBase, request.documentId(),
                topK, threshold, chatModel.modelName(), request.question());
        if (properties.getCache().isEnabled()) {
            Optional<AskResponse> cached = qaCache.get(cacheKey);
            if (cached.isPresent()) {
                log.debug("问答缓存命中: {}", cacheKey);
                return cached.get().markCached().withElapsed(System.currentTimeMillis() - start);
            }
        }

        List<RetrievedChunk> chunks = retrievalService.retrieve(
                request.question(), knowledgeBase, request.documentId(), topK, threshold);
        List<AskResponse.Source> sources = toSources(chunks);

        String answer;
        if (chunks.isEmpty() && !properties.getLlm().isAnswerWithoutContext()) {
            answer = NO_CONTEXT_ANSWER;
        } else {
            String userPrompt = PromptBuilder.buildUserPrompt(
                    request.question(), chunks, properties.getMaxContextChars());
            answer = chatModel.chat(PromptBuilder.SYSTEM_PROMPT, userPrompt);
        }

        AskResponse response = new AskResponse(request.question(), answer, false,
                chatModel.modelName(), System.currentTimeMillis() - start, sources);

        // 无召回结果不入缓存，避免新增文档后仍返回旧结论
        if (properties.getCache().isEnabled() && !chunks.isEmpty()) {
            qaCache.put(cacheKey, response);
        }
        return response;
    }

    private static List<AskResponse.Source> toSources(List<RetrievedChunk> chunks) {
        List<AskResponse.Source> sources = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sources.add(new AskResponse.Source(
                    i + 1, chunk.chunkId(), chunk.documentId(), chunk.fileName(), chunk.chunkIndex(),
                    round(chunk.score()), snippet(chunk.content())));
        }
        return sources;
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 200 ? content : content.substring(0, 200) + "...";
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }
}
