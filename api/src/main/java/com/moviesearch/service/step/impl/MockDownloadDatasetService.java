package com.moviesearch.service.step.impl;

import com.moviesearch.exception.DownloadDatasetServiceException;
import com.moviesearch.service.step.DownloadDatasetService;

import java.util.function.Consumer;

public class MockDownloadDatasetService implements DownloadDatasetService {

    private static final String[] LOGS = {
        "[MOCK] Downloading CMU corpus...",
        "[MOCK] Extracting archive...",
        "[MOCK] Done. 42,306 summaries written."
    };

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        for (String line : LOGS) {
            onLog.accept(line);
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadDatasetServiceException("Interrupted during execution", e);
            }
        }
    }
}
