package com.moviesearch.service.step.impl;

import com.moviesearch.config.ModelProperties;
import com.moviesearch.exception.ExportModelServiceException;
import com.moviesearch.service.PythonScriptRunner;
import com.moviesearch.service.step.ExportModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static com.moviesearch.exception.ExportModelServiceException.EXPORT_FAILED;
import static com.moviesearch.exception.ExportModelServiceException.QUANTIZATION_FAILED;

@Service
public class ExportModelServiceImpl implements ExportModelService {

    private static final Path MODEL_ONNX = Path.of("all-minilm-l6-v2", "1", "model.onnx");

    private final PythonScriptRunner pythonScriptRunner;
    private final Path scriptDir;
    private final Path modelDir;

    @Autowired
    public ExportModelServiceImpl(ModelProperties props, PythonScriptRunner pythonScriptRunner) {
        this.scriptDir = Path.of(props.getScriptDir());
        this.modelDir = Path.of(props.getModelDir());
        this.pythonScriptRunner = pythonScriptRunner;
    }

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        Path modelOnnx = modelDir.resolve(MODEL_ONNX);
        if (!force && Files.exists(modelOnnx)) {
            onLog.accept("Model already exported, skipping.");
            return;
        }
        try {
            pythonScriptRunner.run(scriptDir.resolve("02_export_model.py"), onLog);
            if (!Files.exists(modelOnnx))
                throw new ExportModelServiceException(QUANTIZATION_FAILED);
            onLog.accept("Done. model.onnx written.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExportModelServiceException("Interrupted during execution", e);
        } catch (ExportModelServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new ExportModelServiceException(EXPORT_FAILED, e);
        }
    }
}
