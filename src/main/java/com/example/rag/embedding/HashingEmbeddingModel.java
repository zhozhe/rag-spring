package com.example.rag.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地离线向量化实现（dev / 单元测试用）：基于中文单字 + 二元词组、英文单词的 hashing trick。
 * 语义上不等价于真实 Embedding 模型，但对"关键词重合度"具有稳定的区分能力，
 * 因此可以在没有 Ollama 的环境下验证分块、检索、阈值与召回率链路。
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    private final int dimension;

    public HashingEmbeddingModel(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须为正数");
        }
        this.dimension = dimension;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> result = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            result.add(Embedding.from(vectorOf(segment.text())));
        }
        return Response.from(result);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    public float[] vectorOf(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : tokenize(text)) {
            int hash = token.hashCode();
            vector[Math.floorMod(hash, dimension)] += 1.0f;
            vector[Math.floorMod(hash * 0x9E3779B1, dimension)] += 0.5f;
        }
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) Math.log1p(vector[i]);
        }
        return l2Normalize(vector);
    }

    private static float[] l2Normalize(float[] vector) {
        double squareSum = 0.0;
        for (float v : vector) {
            squareSum += v * v;
        }
        if (squareSum > 0) {
            double norm = Math.sqrt(squareSum);
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    private static List<String> tokenize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flush(latin, tokens);
                tokens.add(String.valueOf(c));
                if (i + 1 < lower.length() && isCjk(lower.charAt(i + 1))) {
                    tokens.add(String.valueOf(new char[]{c, lower.charAt(i + 1)}));
                }
            } else if (c < 128 && Character.isLetterOrDigit(c)) {
                latin.append(c);
            } else {
                flush(latin, tokens);
            }
        }
        flush(latin, tokens);
        return tokens;
    }

    private static void flush(StringBuilder latin, List<String> tokens) {
        if (latin.length() >= 2) {
            tokens.add(latin.toString());
        }
        latin.setLength(0);
    }

    private static boolean isCjk(char c) {
        return c >= '\u4E00' && c <= '\u9FFF';
    }
}
