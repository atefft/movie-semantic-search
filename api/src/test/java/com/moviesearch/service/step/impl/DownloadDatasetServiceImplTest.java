package com.moviesearch.service.step.impl;

import com.moviesearch.config.DatasetProperties;
import com.moviesearch.exception.DownloadDatasetServiceException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.moviesearch.exception.DownloadDatasetServiceException.DOWNLOAD_FAILED;
import static com.moviesearch.exception.DownloadDatasetServiceException.EXTRACTION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadDatasetServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    HttpClient httpClient;

    @Mock
    HttpResponse<byte[]> response;

    DownloadDatasetServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        DatasetProperties props = new DatasetProperties();
        props.setCorpusUrl("http://test.local/corpus.tar.gz");
        props.setDataDir(tempDir);
        service = new DownloadDatasetServiceImpl(httpClient, props);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private byte[] buildTarGz(String entryName, String content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] bytes = content.getBytes();
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
        return baos.toByteArray();
    }

    @Test
    void execute_happyPath() throws Exception {
        String summaryContent = "line1\nline2\nline3\n";
        byte[] archive = buildTarGz("MovieSummaries/plot_summaries.txt", summaryContent);
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(archive);

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs).hasSize(3);
        assertThat(logs.get(0)).startsWith("Downloading");
        assertThat(logs.get(1)).startsWith("Extracting");
        assertThat(logs.get(2)).isEqualTo("Done. 3 summaries written.");
        assertThat(tempDir.resolve("MovieSummaries/plot_summaries.txt")).exists();
    }

    @Test
    void execute_largeCount_formatsWithComma() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1234; i++) sb.append("line\n");
        byte[] archive = buildTarGz("MovieSummaries/plot_summaries.txt", sb.toString());
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(archive);

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs.get(2)).endsWith("1,234 summaries written.");
    }

    @Test
    void execute_httpErrorStatus_throwsDownloadFailed() throws Exception {
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(404);

        assertThatThrownBy(() -> service.execute(log -> {}, false))
            .isInstanceOf(DownloadDatasetServiceException.class)
            .hasMessage(DOWNLOAD_FAILED);
    }

    @Test
    void execute_networkError_throwsDownloadFailed() throws Exception {
        doThrow(new IOException("connection refused")).when(httpClient).send(any(), any());

        assertThatThrownBy(() -> service.execute(log -> {}, false))
            .isInstanceOf(DownloadDatasetServiceException.class)
            .hasMessage(DOWNLOAD_FAILED)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void execute_corruptArchive_throwsExtractionFailed() throws Exception {
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("not a tar.gz".getBytes());

        assertThatThrownBy(() -> service.execute(log -> {}, false))
            .isInstanceOf(DownloadDatasetServiceException.class)
            .hasMessage(EXTRACTION_FAILED);
    }

    @Test
    void execute_interrupted_setsInterruptFlag() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(httpClient).send(any(), any());

        assertThatThrownBy(() -> service.execute(log -> {}, false))
            .isInstanceOf(DownloadDatasetServiceException.class)
            .hasMessage("Interrupted during execution");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void execute_downloadsBeforeExtraction_logsOrder() throws Exception {
        String summaryContent = "line1\n";
        byte[] archive = buildTarGz("MovieSummaries/plot_summaries.txt", summaryContent);
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(archive);

        List<String> logs = new ArrayList<>();
        service.execute(logs::add, false);

        assertThat(logs.get(0)).startsWith("Downloading");
        assertThat(logs.get(1)).startsWith("Extracting");
        assertThat(logs.get(2)).startsWith("Done.");
    }
}
