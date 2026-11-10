package com.example.rag;

import com.example.rag.dto.AskRequest;
import com.example.rag.dto.AskResponse;
import com.example.rag.dto.UploadResponse;
import com.example.rag.model.KnowledgeDocument;
import com.example.rag.repository.DocumentRepository;
import com.example.rag.service.DocumentService;
import com.example.rag.service.QaService;
import com.example.rag.vectorstore.ChunkStore;
import com.example.rag.vectorstore.InMemoryChunkStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端链路测试（dev profile，零外部中间件）：
 * 上传 -> 异步解析/切分/向量化/入库 -> 检索问答 -> 缓存命中 -> 删除与缓存失效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class RagIntegrationTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private QaService qaService;
    @Autowired
    private ChunkStore chunkStore;

    @Test
    void uploadRetrieveAskCacheAndDelete() throws InterruptedException {
        String kb = "it-" + UUID.randomUUID().toString().substring(0, 8);
        String text = "员工年假申请流程：员工需在内部系统提交年假申请，提前三个工作日提交，"
                + "由直属主管审批，最后由人力资源部备案归档。年假余额可在系统自助查询。";
        MockMultipartFile file = new MockMultipartFile("file", "员工手册.md", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        UploadResponse upload = documentService.upload(file, kb);
        assertThat(upload.status()).isEqualTo(KnowledgeDocument.STATUS_PROCESSING);
        assertThat(awaitStatus(upload.documentId())).isEqualTo(KnowledgeDocument.STATUS_READY);
        assertThat(chunkStore).isInstanceOf(InMemoryChunkStore.class);
        assertThat(((InMemoryChunkStore) chunkStore).size()).isGreaterThan(0);

        AskRequest request = new AskRequest("年假申请需要提前几天提交？", kb, null, 5, 0.5);
        AskResponse first = qaService.ask(request);
        assertThat(first.answer()).isNotBlank();
        assertThat(first.sources()).isNotEmpty();
        assertThat(first.sources().get(0).fileName()).isEqualTo("员工手册.md");
        assertThat(first.cached()).isFalse();

        AskResponse second = qaService.ask(request);
        assertThat(second.cached()).isTrue();
        assertThat(second.answer()).isEqualTo(first.answer());

        documentService.delete(upload.documentId());
        assertThat(documentRepository.findById(upload.documentId())).isEmpty();

        AskResponse afterDelete = qaService.ask(request);
        assertThat(afterDelete.cached()).isFalse();
        assertThat(afterDelete.sources()).isEmpty();
    }

    private String awaitStatus(String documentId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String status = documentRepository.findById(documentId)
                    .map(KnowledgeDocument::getStatus)
                    .orElse(null);
            if (status != null && !KnowledgeDocument.STATUS_PROCESSING.equals(status)) {
                return status;
            }
            Thread.sleep(50);
        }
        return "TIMEOUT";
    }
}
