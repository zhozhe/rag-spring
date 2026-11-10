package com.example.rag.controller;

import com.example.rag.cache.QaCache;
import com.example.rag.common.ApiResponse;
import com.example.rag.dto.AskRequest;
import com.example.rag.dto.AskResponse;
import com.example.rag.service.QaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa")
@RequiredArgsConstructor
public class QaController {

    private final QaService qaService;
    private final QaCache qaCache;

    @PostMapping("/ask")
    public ApiResponse<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        return ApiResponse.ok(qaService.ask(request));
    }

    /** 手动清理某个知识库的问答缓存 */
    @DeleteMapping("/cache")
    public ApiResponse<String> clearCache(
            @RequestParam(value = "knowledgeBase", defaultValue = "default") String knowledgeBase) {
        qaCache.invalidateKnowledgeBase(knowledgeBase);
        return ApiResponse.ok("缓存已清理: " + knowledgeBase);
    }
}
