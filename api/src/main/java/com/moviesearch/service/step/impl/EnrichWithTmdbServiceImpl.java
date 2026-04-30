package com.moviesearch.service.step.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesearch.config.ProjectProperties;
import com.moviesearch.config.QdrantProperties;
import com.moviesearch.config.TmdbProperties;
import com.moviesearch.exception.EnrichWithTmdbServiceException;
import com.moviesearch.service.PythonScriptRunner;
import com.moviesearch.service.step.EnrichWithTmdbService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class EnrichWithTmdbServiceImpl implements EnrichWithTmdbService {

    private static final String SCROLL_BODY =
        "{\"filter\":{\"must\":[{\"is_not_null\":{\"key\":\"thumbnail_url\"}}]},\"limit\":1}";

    private final PythonScriptRunner pythonScriptRunner;
    private final QdrantProperties qdrantProperties;
    private final TmdbProperties tmdbProperties;
    private final ProjectProperties projectProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public EnrichWithTmdbServiceImpl(PythonScriptRunner pythonScriptRunner,
                                      QdrantProperties qdrantProperties,
                                      TmdbProperties tmdbProperties,
                                      ProjectProperties projectProperties) {
        this(pythonScriptRunner, qdrantProperties, tmdbProperties, projectProperties,
             HttpClient.newHttpClient(), new ObjectMapper());
    }

    EnrichWithTmdbServiceImpl(PythonScriptRunner pythonScriptRunner,
                               QdrantProperties qdrantProperties,
                               TmdbProperties tmdbProperties,
                               ProjectProperties projectProperties,
                               HttpClient httpClient,
                               ObjectMapper objectMapper) {
        this.pythonScriptRunner = pythonScriptRunner;
        this.qdrantProperties = qdrantProperties;
        this.tmdbProperties = tmdbProperties;
        this.projectProperties = projectProperties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(Consumer<String> onLog, boolean force) {
        if (!force && isAlreadyEnriched()) {
            onLog.accept("TMDB enrichment already done, skipping.");
            return;
        }
        Path script = projectProperties.getRoot().resolve("pipeline/05_enrich_tmdb.py");
        try {
            pythonScriptRunner.run(script, onLog, Map.of("TMDB_API_KEY", tmdbProperties.getApiKey()));
        } catch (IOException e) {
            throw new EnrichWithTmdbServiceException(EnrichWithTmdbServiceException.ENRICHMENT_FAILED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnrichWithTmdbServiceException(EnrichWithTmdbServiceException.ENRICHMENT_FAILED, e);
        }
    }

    private boolean isAlreadyEnriched() {
        try {
            String url = qdrantProperties.getBaseUrl() + "/collections/movies/points/scroll";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(SCROLL_BODY))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            ScrollResult result = objectMapper.readValue(response.body(), ScrollResult.class);
            return result.result != null && result.result.points != null && !result.result.points.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static class ScrollResult {
        public Result result;

        static class Result {
            public List<Object> points;
        }
    }
}
