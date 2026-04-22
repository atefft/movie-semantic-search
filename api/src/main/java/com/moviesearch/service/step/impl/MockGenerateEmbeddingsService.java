package com.moviesearch.service.step.impl;

import com.moviesearch.exception.GenerateEmbeddingsServiceException;
import com.moviesearch.service.step.GenerateEmbeddingsService;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class MockGenerateEmbeddingsService implements GenerateEmbeddingsService {

    private static final String[] LOGS = {
        "[MOCK] Warming up Triton...",
        "[MOCK] Embedding batch 1/663...",
        "[MOCK] Embedding batch 663/663...",
        "[MOCK] Done. embeddings.npy written."
    };

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        for (String line : LOGS) {
            onLog.accept(line);
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GenerateEmbeddingsServiceException("Interrupted during execution", e);
            }
        }
    }
}
