package com.moviesearch.service.step.impl;

import com.moviesearch.exception.EnrichWithTmdbServiceException;
import com.moviesearch.service.step.EnrichWithTmdbService;

import java.util.function.Consumer;

public class MockEnrichWithTmdbService implements EnrichWithTmdbService {

    private static final String[] LOGS = {
        "[MOCK] Fetching TMDB metadata...",
        "[MOCK] Enriching batch 1/200...",
        "[MOCK] Done. 42,306 points enriched."
    };

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        for (String line : LOGS) {
            onLog.accept(line);
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EnrichWithTmdbServiceException("Interrupted during execution", e);
            }
        }
    }
}
