package com.example.rag.document;

import com.example.rag.common.BusinessException;
import com.example.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 自己封装的文档解析层：纯文本直接按 UTF-8 读取，二进制格式交给 Apache Tika，
 * 统一输出归一化后的纯文本，供后续 LangChain4j 分块器使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TikaDocumentParser implements DocumentParser {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".markdown", ".csv", ".json", ".xml", ".html", ".htm", ".log", ".yaml", ".yml");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".md", ".markdown", ".csv", ".json", ".xml", ".html", ".htm", ".log", ".yaml", ".yml",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx");

    private final RagProperties properties;

    @Override
    public void validate(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new BusinessException("文件内容为空");
        }
        long maxBytes = (long) properties.getMaxFileSizeMb() * 1024 * 1024;
        if (content.length > maxBytes) {
            throw new BusinessException(413, "文件大小超过 " + properties.getMaxFileSizeMb() + " MB 限制");
        }
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String extension = extensionOf(lowerName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("不支持的文件类型：" + extension);
        }
    }

    @Override
    public ParsedDocument parse(byte[] content, String fileName) {
        validate(content, fileName);
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String extension = extensionOf(lowerName);

        String raw;
        String contentType;
        if (TEXT_EXTENSIONS.contains(extension)) {
            raw = readUtf8(content);
            contentType = "text/" + extension.substring(1);
        } else {
            Tika tika = new Tika();
            tika.setMaxStringLength(properties.getMaxTextChars());
            try (InputStream in = new ByteArrayInputStream(content)) {
                raw = tika.parseToString(in);
                contentType = tika.detect(content);
            } catch (Exception e) {
                log.warn("文档解析失败, fileName={}", fileName, e);
                throw new BusinessException("文档解析失败：" + e.getMessage(), e);
            }
        }

        String text = normalize(raw);
        if (text.length() > properties.getMaxTextChars()) {
            log.warn("文档文本过长，已截断: {} -> {}", text.length(), properties.getMaxTextChars());
            text = text.substring(0, properties.getMaxTextChars());
        }
        if (text.isBlank()) {
            throw new BusinessException("未能从文件中提取到有效文本内容");
        }
        return new ParsedDocument(text, fileName, contentType);
    }

    private static String readUtf8(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t' || c >= ' ') {
                sb.append(c);
            }
        }
        return sb.toString()
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }
}
