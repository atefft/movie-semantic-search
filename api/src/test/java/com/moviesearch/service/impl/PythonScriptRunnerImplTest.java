package com.moviesearch.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PythonScriptRunnerImplTest {

    @TempDir
    Path tempDir;

    @Mock
    PythonScriptRunnerImpl.ProcessLauncher launcher;

    @Mock
    Process process;

    PythonScriptRunnerImpl service;

    @BeforeEach
    void setUp() {
        service = new PythonScriptRunnerImpl(launcher);
    }

    @Test
    void run_happyPath() throws Exception {
        doReturn(process).when(launcher).launch(any());
        byte[] output = "line1\nline2\n".getBytes();
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output));
        when(process.waitFor()).thenReturn(0);

        List<String> logs = new ArrayList<>();
        service.run(tempDir.resolve("script.py"), logs::add);

        assertThat(logs).containsExactly("line1", "line2");
    }

    @Test
    void run_nonZeroExitCode_throwsIOException() throws Exception {
        doReturn(process).when(launcher).launch(any());
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor()).thenReturn(1);

        assertThatThrownBy(() -> service.run(tempDir.resolve("script.py"), line -> {}))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("exit code: 1");
    }

    @Test
    void run_ioErrorLaunching_propagatesIOException() throws IOException {
        doThrow(new IOException("launch failed")).when(launcher).launch(any());

        assertThatThrownBy(() -> service.run(tempDir.resolve("script.py"), line -> {}))
            .isInstanceOf(IOException.class)
            .hasMessage("launch failed");
    }

    @Test
    void run_interrupted_propagatesInterruptedException() throws Exception {
        doReturn(process).when(launcher).launch(any());
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor()).thenThrow(new InterruptedException("interrupted"));

        assertThatThrownBy(() -> service.run(tempDir.resolve("script.py"), line -> {}))
            .isInstanceOf(InterruptedException.class);
    }
}
