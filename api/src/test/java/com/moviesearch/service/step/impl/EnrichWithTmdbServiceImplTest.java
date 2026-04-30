package com.moviesearch.service.step.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesearch.config.ProjectProperties;
import com.moviesearch.config.QdrantProperties;
import com.moviesearch.config.TmdbProperties;
import com.moviesearch.exception.EnrichWithTmdbServiceException;
import com.moviesearch.service.PythonScriptRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrichWithTmdbServiceImplTest {

    @Mock PythonScriptRunner pythonScriptRunner;
    @Mock QdrantProperties qdrantProperties;
    @Mock TmdbProperties tmdbProperties;
    @Mock ProjectProperties projectProperties;
    @Mock HttpClient httpClient;
    @Mock HttpResponse<String> scrollResponse;

    private EnrichWithTmdbServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EnrichWithTmdbServiceImpl(
            pythonScriptRunner, qdrantProperties, tmdbProperties, projectProperties, httpClient, objectMapper);
        when(qdrantProperties.getBaseUrl()).thenReturn("http://localhost:6333");
        when(tmdbProperties.getApiKey()).thenReturn("test-api-key");
        when(projectProperties.getRoot()).thenReturn(Path.of("/project"));
    }

    private void mockScrollResponse(String body, int statusCode) throws Exception {
        doReturn(scrollResponse).when(httpClient).send(any(), any());
        when(scrollResponse.statusCode()).thenReturn(statusCode);
        when(scrollResponse.body()).thenReturn(body);
    }

    @Test
    void execute_alreadyEnriched_forceFalse_skipsAndLogs() throws Exception {
        mockScrollResponse("{\"result\":{\"points\":[{\"id\":1}]}}", 200);

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs).containsExactly("TMDB enrichment already done, skipping.");
        verifyNoInteractions(pythonScriptRunner);
    }

    @Test
    void execute_alreadyEnriched_forceTrue_runsScript() throws Exception {
        mockScrollResponse("{\"result\":{\"points\":[{\"id\":1}]}}", 200);

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, true);

        verify(pythonScriptRunner).run(any(Path.class), any(), any());
        assertThat(logs).doesNotContain("TMDB enrichment already done, skipping.");
    }

    @Test
    void execute_emptyPoints_forceFalse_runsScript() throws Exception {
        mockScrollResponse("{\"result\":{\"points\":[]}}", 200);

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(Path.class), any(), any());
    }

    @Test
    void execute_scrollHttp404_forceFalse_runsScript() throws Exception {
        doReturn(scrollResponse).when(httpClient).send(any(), any());
        when(scrollResponse.statusCode()).thenReturn(404);

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(Path.class), any(), any());
    }

    @Test
    void execute_scrollHttp500_forceFalse_runsScript() throws Exception {
        doReturn(scrollResponse).when(httpClient).send(any(), any());
        when(scrollResponse.statusCode()).thenReturn(500);

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(Path.class), any(), any());
    }

    @Test
    void execute_scrollIOException_forceFalse_runsScript() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(Path.class), any(), any());
    }

    @Test
    void execute_scrollInterruptedException_forceFalse_runsScript() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new InterruptedException("interrupted"));

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(Path.class), any(), any());
    }

    @Test
    void execute_malformedJson_forceFalse_runsScript() throws Exception {
        doReturn(scrollResponse).when(httpClient).send(any(), any());
        when(scrollResponse.statusCode()).thenReturn(200);
        when(scrollResponse.body()).thenReturn("not-json");

        service.execute(line -> {}, false);

        verify(pythonScriptRunner).run(any(Path.class), any(), any());
    }

    @Test
    void execute_runsScriptWithTmdbApiKeyEnvVar() throws Exception {
        mockScrollResponse("{\"result\":{\"points\":[]}}", 200);

        service.execute(line -> {}, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonScriptRunner).run(any(Path.class), any(), envCaptor.capture());
        assertThat(envCaptor.getValue()).containsEntry("TMDB_API_KEY", "test-api-key");
    }

    @Test
    void execute_scriptIOException_throwsEnrichWithTmdbServiceException() throws Exception {
        mockScrollResponse("{\"result\":{\"points\":[]}}", 200);
        IOException cause = new IOException("script failed");
        doThrow(cause).when(pythonScriptRunner).run(any(Path.class), any(), any());

        assertThatThrownBy(() -> service.execute(line -> {}, false))
            .isInstanceOf(EnrichWithTmdbServiceException.class)
            .hasMessage(EnrichWithTmdbServiceException.ENRICHMENT_FAILED)
            .hasCause(cause);
    }

    @Test
    void execute_scriptInterruptedException_throwsAndSetsInterruptFlag() throws Exception {
        mockScrollResponse("{\"result\":{\"points\":[]}}", 200);
        InterruptedException cause = new InterruptedException("interrupted");
        doThrow(cause).when(pythonScriptRunner).run(any(Path.class), any(), any());

        Thread.interrupted(); // clear existing flag
        assertThatThrownBy(() -> service.execute(line -> {}, false))
            .isInstanceOf(EnrichWithTmdbServiceException.class)
            .hasMessage(EnrichWithTmdbServiceException.ENRICHMENT_FAILED)
            .hasCause(cause);

        assertThat(Thread.interrupted()).isTrue();
    }
}
