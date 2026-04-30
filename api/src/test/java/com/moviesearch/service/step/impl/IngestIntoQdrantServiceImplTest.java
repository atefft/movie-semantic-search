package com.moviesearch.service.step.impl;

import com.moviesearch.config.ProjectProperties;
import com.moviesearch.config.QdrantProperties;
import com.moviesearch.exception.IngestIntoQdrantServiceException;
import com.moviesearch.service.PythonScriptRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.moviesearch.exception.IngestIntoQdrantServiceException.INGESTION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestIntoQdrantServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    PythonScriptRunner pythonScriptRunner;

    @Mock
    HttpClient httpClient;

    @Mock
    HttpResponse<String> httpResponse;

    IngestIntoQdrantServiceImpl service;

    @BeforeEach
    void setUp() {
        ProjectProperties projectProps = new ProjectProperties();
        projectProps.setRoot(tempDir.toString());

        QdrantProperties qdrantProps = new QdrantProperties();
        qdrantProps.setBaseUrl("http://qdrant.local:6333");

        service = new IngestIntoQdrantServiceImpl(pythonScriptRunner, projectProps, qdrantProps, httpClient);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private void stubHttp(int status, String body) throws IOException, InterruptedException {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(status);
        if (body != null) {
            when(httpResponse.body()).thenReturn(body);
        }
    }

    private static String qdrantBody(long pointsCount) {
        return "{\"result\":{\"points_count\":" + pointsCount + "}}";
    }

    @Test
    void execute_pointsCountPositive_forceFalse_skips() throws Exception {
        stubHttp(200, qdrantBody(100));

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs).containsExactly("Qdrant movies collection already ingested, skipping.");
        verify(pythonScriptRunner, never()).run(any(), any());
    }

    @Test
    void execute_pointsCountPositive_forceTrue_runs() throws Exception {
        List<String> logs = new ArrayList<>();
        service.execute(logs::add, true);

        assertThat(logs).noneMatch(l -> l.contains("skipping"));
        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_pointsCountZero_forceFalse_runs() throws Exception {
        stubHttp(200, qdrantBody(0));

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_http404_runs() throws Exception {
        stubHttp(404, null);

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_http500_runs() throws Exception {
        stubHttp(500, null);

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_httpClientIoException_runs() throws Exception {
        doThrow(new IOException("connection refused")).when(httpClient).send(any(), any());

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_httpClientInterruptedException_runs() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(httpClient).send(any(), any());
        Thread.interrupted(); // clear flag set inside isAlreadyIngested

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_malformedJson_runs() throws Exception {
        stubHttp(200, "not-json");

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(), any());
    }

    @Test
    void execute_runThrowsIoException_throwsIngestionFailed() throws Exception {
        doThrow(new IOException("script failed")).when(pythonScriptRunner).run(any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, true))
            .isInstanceOf(IngestIntoQdrantServiceException.class)
            .hasMessage(INGESTION_FAILED)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void execute_runThrowsInterrupted_throwsAndSetsFlag() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(pythonScriptRunner).run(any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, true))
            .isInstanceOf(IngestIntoQdrantServiceException.class)
            .hasMessage("Interrupted during execution");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void execute_stdoutForwardedInOrder() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onLog = (Consumer<String>) inv.getArgument(1);
            onLog.accept("line1");
            onLog.accept("line2");
            return null;
        }).when(pythonScriptRunner).run(any(), any());

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, true);

        assertThat(logs.indexOf("line1")).isGreaterThanOrEqualTo(0);
        assertThat(logs.indexOf("line2")).isGreaterThan(logs.indexOf("line1"));
    }

    @Test
    void execute_collectionsUrlIncludesBaseUrl() throws Exception {
        stubHttp(200, qdrantBody(0));

        service.execute(line -> {}, false);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString())
            .isEqualTo("http://qdrant.local:6333/collections/movies");
    }
}
