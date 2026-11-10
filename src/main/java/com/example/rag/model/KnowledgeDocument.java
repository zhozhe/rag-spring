package com.example.rag.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeDocument {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private String id;
    private String fileName;
    private String knowledgeBase;
    private String contentType;
    private long fileSize;
    private int chunkCount;
    private String status;
    private String errorMessage;
    private String storagePath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
