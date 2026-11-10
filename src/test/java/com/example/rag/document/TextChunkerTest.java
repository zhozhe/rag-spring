package com.example.rag.document;

import com.example.rag.config.RagProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    private static RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(200);
        properties.setChunkOverlap(20);
        properties.setMinChunkChars(10);
        return properties;
    }

    @Test
    void splitsLongDocumentIntoBoundedSegments() {
        String text = ("企业知识库支持文档上传、向量化入库与相似度检索。"
                + "员工可以在系统内检索制度条文，并基于召回片段向大模型提问。\n\n").repeat(40);

        List<TextSegment> segments = new TextChunker(properties()).split(text, new Metadata());

        assertThat(segments).hasSizeGreaterThan(3);
        assertThat(segments).allSatisfy(segment ->
                assertThat(segment.text().length()).isLessThanOrEqualTo(300));
    }

    @Test
    void keepsOriginalMetadataAndEnrichesIndex() {
        Metadata metadata = new Metadata().put("document_id", "doc-1");
        String text = "第一段：年假申请需要提前提交。\n\n".repeat(20);

        List<TextSegment> segments = new TextChunker(properties()).split(text, metadata);

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment ->
                assertThat(segment.metadata().getString("document_id")).isEqualTo("doc-1"));
        assertThat(segments.get(0).metadata().getString("index")).isNotNull();
    }

    @Test
    void filtersBlankAndTinySegments() {
        List<TextSegment> segments = new TextChunker(properties()).split("  \n\n 短 \n\n ", new Metadata());
        assertThat(segments).isEmpty();
    }

    @Test
    void rejectsInvalidChunkParameters() {
        TextChunker chunker = new TextChunker(properties());
        assertThatThrownBy(() -> chunker.split("一些文本", new Metadata(), 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.split("一些文本", new Metadata(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesLargeDocumentInManyChunks() {
        String large = ("知识库分片处理用于验证大文档不会一次性送入向量模型。").repeat(500);
        List<TextSegment> segments = new TextChunker(properties()).split(large, new Metadata());
        assertThat(segments.size()).isGreaterThan(20);
    }
}
