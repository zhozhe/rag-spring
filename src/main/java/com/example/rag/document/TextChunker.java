package com.example.rag.document;

import com.example.rag.config.RagProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LangChain4j 递归分块器的文本切分封装：
 * 优先按段落切，段落过长再依次退化为行、句子、词、字符，并保留重叠以减少语义截断。
 */
@Component
@RequiredArgsConstructor
public class TextChunker {

    private final RagProperties properties;

    public List<TextSegment> split(String text, Metadata metadata) {
        return split(text, metadata, properties.getChunkSize(), properties.getChunkOverlap());
    }

    /**
     * 允许传入自定义 chunk 大小与重叠，供召回率调优测试对比使用。
     */
    public List<TextSegment> split(String text, Metadata metadata, int chunkSize, int overlapSize) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (chunkSize <= 0 || overlapSize < 0 || overlapSize >= chunkSize) {
            throw new IllegalArgumentException("分块参数非法: chunkSize=" + chunkSize + ", overlap=" + overlapSize);
        }

        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, overlapSize);
        Document document = Document.from(text, metadata == null ? new Metadata() : metadata);

        List<TextSegment> result = new ArrayList<>();
        for (TextSegment segment : splitter.split(document)) {
            if (segment == null || segment.text() == null) {
                continue;
            }
            String cleaned = segment.text().trim();
            if (cleaned.length() < properties.getMinChunkChars()) {
                continue;
            }
            result.add(TextSegment.from(cleaned, segment.metadata()));
        }
        return result;
    }
}
//自定义片段大小+重叠参数 设置 文本分割器,对文本内容进行切片,并去除首尾空白字符