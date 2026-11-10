package com.example.rag.controller;

import com.example.rag.common.ApiResponse;
import com.example.rag.dto.DocumentVO;
import com.example.rag.dto.UploadResponse;
import com.example.rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /** 文档上传并触发异步向量入库 */
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<UploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "knowledgeBase", defaultValue = "default") String knowledgeBase) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(documentService.upload(file, knowledgeBase)));
    }

    @GetMapping
    public ApiResponse<List<DocumentVO>> list(
            @RequestParam(value = "knowledgeBase", required = false) String knowledgeBase) {
        return ApiResponse.ok(documentService.list(knowledgeBase));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentVO> get(@PathVariable String id) {
        return ApiResponse.ok(documentService.get(id));
    }

    /** 同时删除元数据、原始文件与向量库中的全部片段 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        documentService.delete(id);
        return ApiResponse.ok(null);
    }
}
