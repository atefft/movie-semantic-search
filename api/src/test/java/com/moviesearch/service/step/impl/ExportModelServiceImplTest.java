package com.moviesearch.service.step.impl;

import com.moviesearch.config.ModelProperties;
import com.moviesearch.exception.ExportModelServiceException;
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

import static com.moviesearch.exception.ExportModelServiceException.EXPORT_FAILED;
import static com.moviesearch.exception.ExportModelServiceException.QUANTIZATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExportModelServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    PythonScriptRunner pythonScriptRunner;

    ExportModelServiceImpl service;

    @BeforeEach
    void setUp() {
        ModelProperties props = new ModelProperties();
        props.setScriptDir(tempDir.resolve("pipeline").toString());
        props.setModelDir(tempDir.toString());
        service = new ExportModelServiceImpl(props, pythonScriptRunner);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private void createModelOnnx() throws IOException {
        Path modelOnnx = tempDir.resolve(Path.of("all-minilm-l6-v2", "1", "model.onnx"));
        Files.createDirectories(modelOnnx.getParent());
        Files.createFile(modelOnnx);
    }

    @Test
    void execute_happyPath() throws Exception {
        doAnswer(inv -> {
            createModelOnnx();
            return null;
        }).when(pythonScriptRunner).run(any(), any());

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs).last().asString().isEqualTo("Done. model.onnx written.");
    }

    @Test
    void execute_modelAlreadyExists_skips() throws Exception {
        createModelOnnx();

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs).anyMatch(l -> l.contains("skipping"));
        verify(pythonScriptRunner, never()).run(any(), any());
    }

    @Test
    void execute_modelAlreadyExists_forceRerun() throws Exception {
        createModelOnnx();
        doAnswer(inv -> null).when(pythonScriptRunner).run(any(), any());

        service.execute(line -> {}, true);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_ioError_throwsExportFailed() throws Exception {
        doThrow(new IOException("launch failed")).when(pythonScriptRunner).run(any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, false))
            .isInstanceOf(ExportModelServiceException.class)
            .hasMessage(EXPORT_FAILED)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void execute_interrupted_setsInterruptFlag() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(pythonScriptRunner).run(any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, false))
            .isInstanceOf(ExportModelServiceException.class)
            .hasMessage("Interrupted during execution");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void execute_modelNotWritten_throwsQuantizationFailed() throws Exception {
        doAnswer(inv -> null).when(pythonScriptRunner).run(any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, false))
            .isInstanceOf(ExportModelServiceException.class)
            .hasMessage(QUANTIZATION_FAILED);
    }

    @Test
    void execute_stdoutForwardedViaRunner() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onLog = (java.util.function.Consumer<String>) inv.getArgument(1);
            onLog.accept("line1");
            onLog.accept("line2");
            createModelOnnx();
            return null;
        }).when(pythonScriptRunner).run(any(), any());

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        int line1Idx = logs.indexOf("line1");
        int line2Idx = logs.indexOf("line2");
        int doneIdx = logs.indexOf("Done. model.onnx written.");
        assertThat(line1Idx).isGreaterThanOrEqualTo(0);
        assertThat(line2Idx).isGreaterThan(line1Idx);
        assertThat(doneIdx).isGreaterThan(line2Idx);
    }
}
