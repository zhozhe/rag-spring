package com.example.rag.service;

import com.example.rag.cache.QaCache;
import com.example.rag.config.RagProperties;
import com.example.rag.dto.AskRequest;
import com.example.rag.dto.AskResponse;
import com.example.rag.llm.ChatModel;
import com.example.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QaServiceTest {

    @Mock
    private RetrievalService retrievalService;
    @Mock
    private ChatModel chatModel;
    @Mock
    private QaCache qaCache;

    private QaService qaService;

    private static final AskRequest REQUEST =
            new AskRequest("年假申请流程是什么？", "default", null, null, null);

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        when(retrievalService.effectiveTopK(any())).thenReturn(5);
        when(retrievalService.effectiveThreshold(any())).thenReturn(0.65);
        when(chatModel.modelName()).thenReturn("test-model");
        qaService = new QaService(retrievalService, chatModel, qaCache, properties);
    }

    @Test
    void cacheHitSkipsVectorSearchAndLlm() {
        AskResponse cached = new AskResponse(REQUEST.question(), "缓存答案", false,
                "test-model", 5, List.of());
        when(qaCache.get(anyString())).thenReturn(Optional.of(cached));

        AskResponse response = qaService.ask(REQUEST);

        assertThat(response.cached()).isTrue();
        assertThat(response.answer()).isEqualTo("缓存答案");
        verify(retrievalService, never()).retrieve(any(), any(), any(), any(), any());
        verify(chatModel, never()).chat(anyString(), anyString());
    }

    @Test
    void emptyRecallReturnsNoticeWithoutCallingLlmOrWritingCache() {
        when(qaCache.get(anyString())).thenReturn(Optional.empty());
        when(retrievalService.retrieve(any(), any(), any(), any(), any())).thenReturn(List.of());

        AskResponse response = qaService.ask(REQUEST);

        assertThat(response.answer()).contains("知识库中没有检索到");
        assertThat(response.sources()).isEmpty();
        verify(chatModel, never()).chat(anyString(), anyString());
        verify(qaCache, never()).put(anyString(), any());
    }

    @Test
    void retrievedChunksAreInjectedIntoPromptAndCached() {
        RetrievedChunk chunk = new RetrievedChunk("c1", "d1", "员工手册.md", "default", 0,
                "年假需提前三个工作日提交并由直属主管审批", 0.91);
        when(qaCache.get(anyString())).thenReturn(Optional.empty());
        when(retrievalService.retrieve(any(), any(), any(), any(), any())).thenReturn(List.of(chunk));
        when(chatModel.chat(anyString(), anyString())).thenReturn("根据资料 [1]，需提前三个工作日提交。");

        AskResponse response = qaService.ask(REQUEST);

        assertThat(response.answer()).contains("[1]");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).fileName()).isEqualTo("员工手册.md");
        assertThat(response.cached()).isFalse();

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(eq(PromptBuilder.SYSTEM_PROMPT), userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("[1]");
        assertThat(userPrompt.getValue()).contains("年假需提前三个工作日提交");
        assertThat(userPrompt.getValue()).contains(REQUEST.question());
        verify(qaCache).put(anyString(), any());
    }

    @Test
    void disablingCacheAlwaysGoesThroughFullChain() {
        RagProperties properties = new RagProperties();
        properties.getCache().setEnabled(false);
        qaService = new QaService(retrievalService, chatModel, qaCache, properties);
        RetrievedChunk chunk = new RetrievedChunk("c1", "d1", "员工手册.md", "default", 0,
                "年假需提前三个工作日提交", 0.9);
        when(retrievalService.retrieve(any(), any(), any(), any(), any())).thenReturn(List.of(chunk));
        when(chatModel.chat(anyString(), anyString())).thenReturn("回答");

        AskResponse response = qaService.ask(REQUEST);

        assertThat(response.answer()).isEqualTo("回答");

        verify(qaCache, never()).get(anyString());
        verify(qaCache, never()).put(anyString(), any());
    }
}
