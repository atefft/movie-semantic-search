package com.moviesearch.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moviesearch.config.QdrantProperties;
import com.moviesearch.config.TmdbProperties;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "qdrant.mock", havingValue = "false", matchIfMissing = false)
public class VectorSearchServiceImpl implements VectorSearchService {

    private static final int OVERSAMPLING_DEFAULT = 20;
    private static final int OVERSAMPLING_MAX = 100;

    private final RestTemplate restTemplate;
    private final QdrantProperties properties;
    private final String searchUrl;
    private final String posterBaseUrl;

    public VectorSearchServiceImpl(RestTemplate restTemplate,
                                   QdrantProperties properties,
                                   TmdbProperties tmdbProperties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.searchUrl = properties.getBaseUrl() + "/collections/movies/points/search";
        String base = tmdbProperties.getPosterBaseUrl();
        this.posterBaseUrl = base == null ? "" : base.replaceAll("/+$", "");
    }

    private String absolutePosterUrl(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        if (stored.startsWith("http://") || stored.startsWith("https://")) {
            return stored;
        }
        if (posterBaseUrl.isEmpty()) {
            return stored;
        }
        return posterBaseUrl + (stored.startsWith("/") ? stored : "/" + stored);
    }

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        int factor = properties.getOversamplingFactor();
        if (factor <= 0 || factor > OVERSAMPLING_MAX) {
            factor = OVERSAMPLING_DEFAULT;
        }

        QdrantSearchRequest body = new QdrantSearchRequest(request.getVector(), request.getLimit() * factor);
        try {
            ResponseEntity<QdrantSearchResponse> resp =
                restTemplate.postForEntity(searchUrl, body, QdrantSearchResponse.class);

            QdrantSearchResponse qdrantBody = resp.getBody();
            if (qdrantBody == null || qdrantBody.result == null) {
                return new VectorSearchResponse(List.of());
            }

            LinkedHashMap<String, List<QdrantResult>> grouped = new LinkedHashMap<>();
            for (QdrantResult r : qdrantBody.result) {
                if (r.payload == null || r.payload.movieId == null) continue;
                grouped.computeIfAbsent(r.payload.movieId, k -> new ArrayList<>()).add(r);
            }

            List<MovieResult> results = grouped.entrySet().stream()
                .map(entry -> {
                    List<QdrantResult> chunks = entry.getValue();
                    QdrantResult best = chunks.get(0);
                    double meanScore = chunks.stream().mapToDouble(c -> c.score).average().orElse(0.0);

                    String matchingSegment = best.payload.chunkText != null
                        ? best.payload.chunkText : best.payload.summarySnippet;
                    String summarySnippet = best.payload.fullSummary != null
                        ? best.payload.fullSummary : best.payload.summarySnippet;

                    return MovieResult.builder()
                        .title(best.payload.title)
                        .year(best.payload.releaseYear)
                        .genres(best.payload.genres)
                        .score((float) meanScore)
                        .summarySnippet(summarySnippet)
                        .matchingSegment(matchingSegment)
                        .thumbnailUrl(absolutePosterUrl(best.payload.thumbnailUrl))
                        .build();
                })
                .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                .limit(request.getLimit())
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
        @JsonProperty("movie_id")      public String movieId;
        public String title;
        @JsonProperty("release_year") public Integer releaseYear;
        public List<String> genres;
        @JsonProperty("chunk_text")   public String chunkText;
        @JsonProperty("full_summary") public String fullSummary;
        @JsonProperty("summary_snippet") public String summarySnippet;
        @JsonProperty("thumbnail_url")   public String thumbnailUrl;
    }
}
