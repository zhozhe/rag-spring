package com.example.rag.service;

import com.example.rag.cache.QaCache;
import com.example.rag.common.BusinessException;
import com.example.rag.config.RagProperties;
import com.example.rag.document.DocumentParser;
import com.example.rag.dto.DocumentVO;
import com.example.rag.dto.UploadResponse;
import com.example.rag.model.KnowledgeDocument;
import com.example.rag.repository.DocumentRepository;
import com.example.rag.vectorstore.ChunkStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentParser documentParser;
    private final DocumentIngestService ingestService;
    private final ChunkStore chunkStore;
    private final QaCache qaCache;
    private final RagProperties properties;

    public UploadResponse upload(MultipartFile file, String knowledgeBase) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "unnamed.txt" : file.getOriginalFilename();
        String kb = knowledgeBase == null || knowledgeBase.isBlank() ? "default" : knowledgeBase.trim();

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败：" + e.getMessage(), e);
        }
        documentParser.validate(content, fileName);

        String id = UUID.randomUUID().toString().replace("-", "");
        String storagePath = storeFile(id, fileName, content);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setFileName(fileName);
        document.setKnowledgeBase(kb);
        document.setContentType(file.getContentType());
        document.setFileSize(content.length);
        document.setChunkCount(0);
        document.setStatus(KnowledgeDocument.STATUS_PROCESSING);
        document.setStoragePath(storagePath);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.insert(document);

        // 解析/切分/向量化耗时较长，交给独立线程池异步执行
        ingestService.ingestAsync(id, content, fileName, kb);
        return new UploadResponse(id, fileName, kb, KnowledgeDocument.STATUS_PROCESSING);
    }

    public List<DocumentVO> list(String knowledgeBase) {
        return documentRepository.findAll(knowledgeBase).stream().map(DocumentVO::from).toList();
    }

    public DocumentVO get(String id) {
        return documentRepository.findById(id)
                .map(DocumentVO::from)
                .orElseThrow(() -> new BusinessException(404, "文档不存在: " + id));
    }

    public void delete(String id) {
        KnowledgeDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "文档不存在: " + id));
        chunkStore.removeByDocumentId(id);
        documentRepository.deleteById(id);
        deleteFile(document.getStoragePath());
        // 文档变更后作废该知识库的问答缓存，避免返回过期答案
        qaCache.invalidateKnowledgeBase(document.getKnowledgeBase());
        log.info("文档已删除: id={}, file={}", id, document.getFileName());
    }

    private String storeFile(String id, String fileName, byte[] content) {
        try {
            Path dir = Paths.get(properties.getStorage().getDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            Path target = dir.resolve(id + "_" + safeName);
            Files.write(target, content);
            return target.toString();
        } catch (IOException e) {
            throw new BusinessException(500, "保存原始文件失败：" + e.getMessage(), e);
        }
    }

    private static void deleteFile(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.warn("删除原始文件失败: path={}, reason={}", path, e.getMessage());
        }
    }
}
