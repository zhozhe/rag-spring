package com.example.rag.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 缓存 Key：知识库 + 检索参数 + 问题 + 模型，参数变化自动产生新 Key。 */
public final class QaCacheKeys {

    private QaCacheKeys() {
    }

    public static String build(String prefix, String knowledgeBase, String documentId,
                               int topK, double minScore, String model, String question) {
        String kb = normalize(knowledgeBase);
        String raw = String.join("|",
                kb,
                normalize(documentId),
                String.valueOf(topK),
                String.format("%.3f", minScore),
                normalize(model),
                question == null ? "" : question.trim());
        return prefix + kb + ":" + sha256(raw);
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }
}
