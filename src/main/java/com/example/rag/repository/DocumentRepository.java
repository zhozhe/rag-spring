package com.example.rag.repository;

import com.example.rag.model.KnowledgeDocument;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository {

    void insert(KnowledgeDocument document);

    void updateStatus(String id, String status, int chunkCount, String errorMessage);

    Optional<KnowledgeDocument> findById(String id);

    List<KnowledgeDocument> findAll(String knowledgeBase);

    void deleteById(String id);
}
