package com.example.rag.document;

public interface DocumentParser {

    /** 上传时同步执行的基础校验（大小、类型），避免异步任务才失败。 */
    void validate(byte[] content, String fileName);

    ParsedDocument parse(byte[] content, String fileName);

    record ParsedDocument(String text, String fileName, String contentType) {
    }
}
