package com.moviesearch.service.step.impl;

import com.moviesearch.config.DatasetProperties;
import com.moviesearch.exception.DownloadDatasetServiceException;
import com.moviesearch.service.step.DownloadDatasetService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static com.moviesearch.exception.DownloadDatasetServiceException.DOWNLOAD_FAILED;
import static com.moviesearch.exception.DownloadDatasetServiceException.EXTRACTION_FAILED;

@Service
public class DownloadDatasetServiceImpl implements DownloadDatasetService {

    private static final String SUMMARIES_FILE = "MovieSummaries/plot_summaries.txt";

    private final HttpClient httpClient;
    private final String corpusUrl;
    private final Path dataDir;

    @Autowired
    public DownloadDatasetServiceImpl(DatasetProperties datasetProperties) {
        this(HttpClient.newBuilder().followRedirects(Redirect.NORMAL).build(),
             datasetProperties);
    }

    DownloadDatasetServiceImpl(HttpClient httpClient, DatasetProperties datasetProperties) {
        this.httpClient = httpClient;
        this.corpusUrl = datasetProperties.getCorpusUrl();
        this.dataDir = datasetProperties.getDataDir();
    }

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        try {
            Files.createDirectories(dataDir);
            onLog.accept("Downloading CMU corpus...");
            byte[] archive = download();
            onLog.accept("Extracting archive...");
            int count = extractAndCount(archive);
            onLog.accept("Done. " + String.format("%,d", count) + " summaries written.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadDatasetServiceException("Interrupted during execution", e);
        } catch (DownloadDatasetServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new DownloadDatasetServiceException(DOWNLOAD_FAILED, e);
        }
    }

    private byte[] download() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(corpusUrl)).build();
        HttpResponse<byte[]> resp = httpClient.send(req, BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200)
            throw new DownloadDatasetServiceException(DOWNLOAD_FAILED);
        return resp.body();
    }

    private int extractAndCount(byte[] archive) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GzipCompressorInputStream(new ByteArrayInputStream(archive)))) {
            int count = 0;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path dest = dataDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    try (OutputStream out = Files.newOutputStream(dest)) {
                        tar.transferTo(out);
                    }
                    if (entry.getName().endsWith("plot_summaries.txt")) {
                        count = countLines(dest);
                    }
                }
            }
            return count;
        } catch (IOException e) {
            throw new DownloadDatasetServiceException(EXTRACTION_FAILED, e);
        }
    }

    private int countLines(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            int n = 0;
            while (reader.readLine() != null) n++;
            return n;
        }
    }
}
