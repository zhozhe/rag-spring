package com.example.rag.repository;

import com.example.rag.model.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcDocumentRepository implements DocumentRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<KnowledgeDocument> MAPPER = (rs, rowNum) -> {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(rs.getString("id"));
        doc.setFileName(rs.getString("file_name"));
        doc.setKnowledgeBase(rs.getString("knowledge_base"));
        doc.setContentType(rs.getString("content_type"));
        doc.setFileSize(rs.getLong("file_size"));
        doc.setChunkCount(rs.getInt("chunk_count"));
        doc.setStatus(rs.getString("status"));
        doc.setErrorMessage(rs.getString("error_message"));
        doc.setStoragePath(rs.getString("storage_path"));
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        doc.setCreatedAt(created == null ? null : created.toLocalDateTime());
        doc.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        return doc;
    };

    @Override
    public void insert(KnowledgeDocument document) {
        LocalDateTime now = LocalDateTime.now();
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        jdbc.update("""
                INSERT INTO knowledge_document
                (id, file_name, knowledge_base, content_type, file_size, chunk_count,
                 status, error_message, storage_path, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                document.getId(), document.getFileName(), document.getKnowledgeBase(),
                document.getContentType(), document.getFileSize(), document.getChunkCount(),
                document.getStatus(), document.getErrorMessage(), document.getStoragePath(),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    @Override
    public void updateStatus(String id, String status, int chunkCount, String errorMessage) {
        jdbc.update("""
                UPDATE knowledge_document
                SET status = ?, chunk_count = ?, error_message = ?, updated_at = ?
                WHERE id = ?
                """, status, chunkCount, errorMessage, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    @Override
    public Optional<KnowledgeDocument> findById(String id) {
        List<KnowledgeDocument> result = jdbc.query(
                "SELECT * FROM knowledge_document WHERE id = ?", MAPPER, id);
        return result.stream().findFirst();
    }

    @Override
    public List<KnowledgeDocument> findAll(String knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.isBlank()) {
            return jdbc.query("SELECT * FROM knowledge_document ORDER BY created_at DESC", MAPPER);
        }
        return jdbc.query("SELECT * FROM knowledge_document WHERE knowledge_base = ? ORDER BY created_at DESC",
                MAPPER, knowledgeBase.trim());
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("DELETE FROM knowledge_document WHERE id = ?", id);
    }
}
