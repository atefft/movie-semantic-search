package com.moviesearch.service.step.impl;

import com.moviesearch.exception.IngestIntoQdrantServiceException;
import com.moviesearch.service.step.IngestIntoQdrantService;
import java.util.function.Consumer;

public class MockIngestIntoQdrantService implements IngestIntoQdrantService {

    private static final String[] LOGS = {
        "[MOCK] Connecting to Qdrant...",
        "[MOCK] Ingesting batch 1/100...",
        "[MOCK] Done. 42,306 points ingested."
    };

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        for (String line : LOGS) {
            onLog.accept(line);
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IngestIntoQdrantServiceException("Interrupted during execution", e);
            }
        }
    }
}
