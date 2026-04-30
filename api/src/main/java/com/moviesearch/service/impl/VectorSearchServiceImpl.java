package com.moviesearch.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moviesearch.config.QdrantProperties;
import com.moviesearch.exception.VectorSearchServiceException;
import com.moviesearch.model.MovieResult;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import com.moviesearch.service.VectorSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@ConditionalOnProperty(name = "qdrant.mock", havingValue = "false", matchIfMissing = false)
public class VectorSearchServiceImpl implements VectorSearchService {

    private final RestTemplate restTemplate;
    private final String searchUrl;

    public VectorSearchServiceImpl(RestTemplate restTemplate, QdrantProperties properties) {
        this.restTemplate = restTemplate;
        this.searchUrl = properties.getBaseUrl() + "/collections/movies/points/search";
    }

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        QdrantSearchRequest body = new QdrantSearchRequest(request.getVector(), request.getLimit());
        try {
            ResponseEntity<QdrantSearchResponse> resp =
                restTemplate.postForEntity(searchUrl, body, QdrantSearchResponse.class);
            List<MovieResult> results = resp.getBody().result.stream()
                .map(r -> MovieResult.builder()
                    .title(r.payload.title)
                    .year(r.payload.releaseYear)
                    .genres(r.payload.genres)
                    .score(r.score)
                    .summarySnippet(r.payload.summarySnippet)
                    .thumbnailUrl(r.payload.thumbnailUrl)
                    .build())
                .toList();
            return new VectorSearchResponse(results);
        } catch (ResourceAccessException e) {
            throw new VectorSearchServiceException(VectorSearchServiceException.CONNECTION_REFUSED, e);
        } catch (HttpStatusCodeException e) {
            throw new VectorSearchServiceException(VectorSearchServiceException.NON_2XX_RESPONSE);
        }
    }

    static class QdrantSearchRequest {
        public float[] vector;
        public int limit;
        @JsonProperty("with_payload") public boolean withPayload = true;

        QdrantSearchRequest(float[] vector, int limit) {
            this.vector = vector;
            this.limit = limit;
        }
    }

    static class QdrantSearchResponse {
        public List<QdrantResult> result;
    }

    static class QdrantResult {
        public float score;
        public QdrantPayload payload;
    }

    static class QdrantPayload {
        public String title;
        @JsonProperty("release_year") public Integer releaseYear;
        public List<String> genres;
        @JsonProperty("summary_snippet") public String summarySnippet;
        @JsonProperty("thumbnail_url") public String thumbnailUrl;
    }
}
