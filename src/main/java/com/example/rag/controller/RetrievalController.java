package com.example.rag.controller;

import com.example.rag.common.ApiResponse;
import com.example.rag.dto.SearchRequest;
import com.example.rag.dto.SearchResponse;
import com.example.rag.model.RetrievedChunk;
import com.example.rag.service.RetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;

    /** 直接查看召回片段与相关度，便于评估召回率、调优 chunk 大小与阈值 */
    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@Valid @RequestBody SearchRequest request) {
        double threshold = retrievalService.effectiveThreshold(request.minScore());
        List<RetrievedChunk> chunks = retrievalService.retrieve(
                request.query(), request.knowledgeBase(), request.documentId(),
                request.topK(), request.minScore());
        List<SearchResponse.Hit> hits = chunks.stream()
                .map(chunk -> new SearchResponse.Hit(chunk.chunkId(), chunk.documentId(), chunk.fileName(),
                        chunk.chunkIndex(), Math.round(chunk.score() * 10000.0) / 10000.0, chunk.content()))
                .toList();
        return ApiResponse.ok(new SearchResponse(request.query(), hits.size(), threshold, hits));
    }
}
