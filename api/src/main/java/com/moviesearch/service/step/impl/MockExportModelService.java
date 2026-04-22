package com.moviesearch.service.step.impl;

import com.moviesearch.exception.ExportModelServiceException;
import com.moviesearch.service.step.ExportModelService;

import java.util.function.Consumer;

public class MockExportModelService implements ExportModelService {

    private static final String[] LOGS = {
        "[MOCK] Exporting all-MiniLM-L6-v2 to ONNX...",
        "[MOCK] Quantizing model...",
        "[MOCK] Done. model.onnx written."
    };

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        for (String line : LOGS) {
            onLog.accept(line);
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExportModelServiceException("Interrupted during execution", e);
            }
        }
    }
}
