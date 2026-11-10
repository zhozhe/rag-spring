package com.example.rag.dto;

import java.util.List;

public record AskResponse(
        String question,
        String answer,
        boolean cached,
        String model,
        long elapsedMs,
        List<Source> sources
) {
    public record Source(
            int index,
            String chunkId,
            String documentId,
            String fileName,
            int chunkIndex,
            double score,
            String snippet
    ) {
    }

    public AskResponse markCached() {
        return new AskResponse(question, answer, true, model, elapsedMs, sources);
    }

    public AskResponse withElapsed(long elapsed) {
        return new AskResponse(question, answer, cached, model, elapsed, sources);
    }
}
