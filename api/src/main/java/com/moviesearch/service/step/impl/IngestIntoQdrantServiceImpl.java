package com.moviesearch.service.step.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesearch.config.ProjectProperties;
import com.moviesearch.config.QdrantProperties;
import com.moviesearch.exception.IngestIntoQdrantServiceException;
import com.moviesearch.service.PythonScriptRunner;
import com.moviesearch.service.step.IngestIntoQdrantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.function.Consumer;

import static com.moviesearch.exception.IngestIntoQdrantServiceException.INGESTION_FAILED;

@Service
public class IngestIntoQdrantServiceImpl implements IngestIntoQdrantService {

    private final PythonScriptRunner pythonScriptRunner;
    private final Path projectRoot;
    private final QdrantProperties qdrantProperties;
    private final HttpClient httpClient;

    @Autowired
    public IngestIntoQdrantServiceImpl(PythonScriptRunner pythonScriptRunner,
                                       ProjectProperties projectProperties,
                                       QdrantProperties qdrantProperties) {
        this(pythonScriptRunner, projectProperties, qdrantProperties, HttpClient.newHttpClient());
    }

    IngestIntoQdrantServiceImpl(PythonScriptRunner pythonScriptRunner,
                                ProjectProperties projectProperties,
                                QdrantProperties qdrantProperties,
                                HttpClient httpClient) {
        this.pythonScriptRunner = pythonScriptRunner;
        this.projectRoot = projectProperties.getRoot();
        this.qdrantProperties = qdrantProperties;
        this.httpClient = httpClient;
    }

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        if (!force && isAlreadyIngested()) {
            onLog.accept("Qdrant movies collection already ingested, skipping.");
            return;
        }
        try {
            pythonScriptRunner.run(projectRoot.resolve("pipeline/04_ingest_qdrant.py"), onLog);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestIntoQdrantServiceException("Interrupted during execution", e);
        } catch (IOException e) {
            throw new IngestIntoQdrantServiceException(INGESTION_FAILED, e);
        }
    }

    private boolean isAlreadyIngested() {
        try {
            String url = qdrantProperties.getBaseUrl() + "/collections/movies";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            CollectionInfo info = new ObjectMapper().readValue(response.body(), CollectionInfo.class);
            return info.result != null && info.result.points_count > 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static class CollectionInfo {
        public Result result;

        static class Result {
            public long points_count;
        }
    }
}
