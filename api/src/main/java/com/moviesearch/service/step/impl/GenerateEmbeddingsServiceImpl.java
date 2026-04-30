package com.moviesearch.service.step.impl;

import com.moviesearch.config.ProjectProperties;
import com.moviesearch.exception.GenerateEmbeddingsServiceException;
import com.moviesearch.service.PythonScriptRunner;
import com.moviesearch.service.step.GenerateEmbeddingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static com.moviesearch.exception.GenerateEmbeddingsServiceException.BATCH_FAILED;

@Service
public class GenerateEmbeddingsServiceImpl implements GenerateEmbeddingsService {

    private static final Path SCRIPT     = Path.of("pipeline", "03_embed_corpus.py");
    private static final Path EMBEDDINGS = Path.of("data", "embeddings", "embeddings.npy");
    private static final Path METADATA   = Path.of("data", "embeddings", "metadata.json");

    private final PythonScriptRunner pythonScriptRunner;
    private final Path projectRoot;

    @Autowired
    public GenerateEmbeddingsServiceImpl(PythonScriptRunner runner, ProjectProperties props) {
        this.pythonScriptRunner = runner;
        this.projectRoot = props.getRoot();
    }

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        if (!force
                && Files.exists(projectRoot.resolve(EMBEDDINGS))
                && Files.exists(projectRoot.resolve(METADATA))) {
            onLog.accept("Embeddings already generated, skipping.");
            return;
        }
        try {
            pythonScriptRunner.run(projectRoot.resolve(SCRIPT), onLog);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GenerateEmbeddingsServiceException("Interrupted during execution", e);
        } catch (IOException e) {
            throw new GenerateEmbeddingsServiceException(BATCH_FAILED, e);
        }
    }
}
