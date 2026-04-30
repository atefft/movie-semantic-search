package com.moviesearch.service.step.impl;

import com.moviesearch.config.ProjectProperties;
import com.moviesearch.exception.GenerateEmbeddingsServiceException;
import com.moviesearch.service.PythonScriptRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.moviesearch.exception.GenerateEmbeddingsServiceException.BATCH_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GenerateEmbeddingsServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    PythonScriptRunner pythonScriptRunner;

    GenerateEmbeddingsServiceImpl service;

    @BeforeEach
    void setUp() {
        ProjectProperties props = new ProjectProperties();
        props.setRoot(tempDir.toString());
        service = new GenerateEmbeddingsServiceImpl(pythonScriptRunner, props);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private void createEmbeddingsNpy() throws IOException {
        Path p = tempDir.resolve(Path.of("data", "embeddings", "embeddings.npy"));
        Files.createDirectories(p.getParent());
        Files.createFile(p);
    }

    private void createMetadataJson() throws IOException {
        Path p = tempDir.resolve(Path.of("data", "embeddings", "metadata.json"));
        Files.createDirectories(p.getParent());
        Files.createFile(p);
    }

    @Test
    void execute_bothFilesExist_forceFalse_skips() throws Exception {
        createEmbeddingsNpy();
        createMetadataJson();

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs).containsExactly("Embeddings already generated, skipping.");
        verify(pythonScriptRunner, never()).run(any(), any());
    }

    @Test
    void execute_bothFilesExist_forceTrue_runs() throws Exception {
        createEmbeddingsNpy();
        createMetadataJson();
        doAnswer(inv -> null).when(pythonScriptRunner).run(any(), any());

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, true);

        verify(pythonScriptRunner).run(any(), any());
        assertThat(logs).doesNotContain("Embeddings already generated, skipping.");
    }

    @Test
    void execute_onlyEmbeddingsNpy_forceFalse_runs() throws Exception {
        createEmbeddingsNpy();
        doAnswer(inv -> null).when(pythonScriptRunner).run(any(), any());

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_onlyMetadataJson_forceFalse_runs() throws Exception {
        createMetadataJson();
        doAnswer(inv -> null).when(pythonScriptRunner).run(any(), any());

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_noFiles_forceFalse_runs() throws Exception {
        doAnswer(inv -> null).when(pythonScriptRunner).run(any(), any());

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_ioError_throwsBatchFailed() throws Exception {
        doThrow(new IOException("launch failed")).when(pythonScriptRunner).run(any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, false))
            .isInstanceOf(GenerateEmbeddingsServiceException.class)
            .hasMessage(BATCH_FAILED)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void execute_interrupted_setsInterruptFlag() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(pythonScriptRunner).run(any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, false))
            .isInstanceOf(GenerateEmbeddingsServiceException.class)
            .hasMessage("Interrupted during execution");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void execute_scriptPathIsProjectRootRelative() throws Exception {
        doAnswer(inv -> null).when(pythonScriptRunner).run(any(), any());

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(
            org.mockito.ArgumentMatchers.eq(tempDir.resolve("pipeline/03_embed_corpus.py")),
            any()
        );
    }

    @Test
    void execute_stdoutForwardedViaRunner() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onLog = (java.util.function.Consumer<String>) inv.getArgument(1);
            onLog.accept("line1");
            onLog.accept("line2");
            return null;
        }).when(pythonScriptRunner).run(any(), any());

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        int line1Idx = logs.indexOf("line1");
        int line2Idx = logs.indexOf("line2");
        assertThat(line1Idx).isGreaterThanOrEqualTo(0);
        assertThat(line2Idx).isGreaterThan(line1Idx);
    }
}
