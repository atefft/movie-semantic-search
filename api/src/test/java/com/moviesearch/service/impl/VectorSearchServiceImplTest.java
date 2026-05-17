package com.moviesearch.service.impl;

import com.moviesearch.config.QdrantProperties;
import com.moviesearch.config.TmdbProperties;
import com.moviesearch.exception.VectorSearchServiceException;
import com.moviesearch.model.MovieResult;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.within;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class VectorSearchServiceImplTest {

    private static final String BASE_URL = "http://qdrant-test:6333";
    private static final String SEARCH_URL = BASE_URL + "/collections/movies/points/search";
    private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w200";
    private static final float[] VECTOR = new float[]{0.1f, 0.2f, 0.3f};

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private VectorSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(1);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        service = new VectorSearchServiceImpl(restTemplate, props, tmdb);
    }

    // ── existing mapping tests (updated: movie_id added to all payloads) ──

    @Test
    void search_happyPath_returnsAllFieldsMapped() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.with_payload").value(true))
            .andExpect(jsonPath("$.limit").value(5))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.92,
                      "payload": {
                        "movie_id": "cast-away",
                        "title": "Cast Away",
                        "release_year": 2000,
                        "genres": ["Drama", "Adventure"],
                        "summary_snippet": "A FedEx executive stranded on an island.",
                        "thumbnail_url": "https://example.com/cast-away.jpg"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        MovieResult result = response.getResults().get(0);
        assertThat(result.getTitle()).isEqualTo("Cast Away");
        assertThat(result.getYear()).isEqualTo(2000);
        assertThat(result.getGenres()).containsExactly("Drama", "Adventure");
        assertThat(result.getScore()).isEqualTo(0.92f);
        assertThat(result.getSummarySnippet()).isEqualTo("A FedEx executive stranded on an island.");
        assertThat(result.getThumbnailUrl()).isEqualTo("https://example.com/cast-away.jpg");

        server.verify();
    }

    @Test
    void search_nullThumbnailUrl_doesNotThrow() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.85,
                      "payload": {
                        "movie_id": "the-martian",
                        "title": "The Martian",
                        "release_year": 2015,
                        "genres": ["Science Fiction"],
                        "summary_snippet": "An astronaut stranded on Mars.",
                        "thumbnail_url": null
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getThumbnailUrl()).isNull();

        server.verify();
    }

    @Test
    void search_multipleResults_allMapped() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.92,
                      "payload": {
                        "movie_id": "m1",
                        "title": "Movie A",
                        "release_year": 2001,
                        "genres": ["Action"],
                        "summary_snippet": "Snippet A",
                        "thumbnail_url": null
                      }
                    },
                    {
                      "score": 0.88,
                      "payload": {
                        "movie_id": "m2",
                        "title": "Movie B",
                        "release_year": 2002,
                        "genres": ["Drama"],
                        "summary_snippet": "Snippet B",
                        "thumbnail_url": null
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Movie A");
        assertThat(response.getResults().get(1).getTitle()).isEqualTo("Movie B");

        server.verify();
    }

    @Test
    void search_networkFailure_throwsConnectionRefusedException() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> service.search(new VectorSearchRequest(VECTOR, 5)))
            .isInstanceOf(VectorSearchServiceException.class)
            .hasMessage(VectorSearchServiceException.CONNECTION_REFUSED);

        server.verify();
    }

    @Test
    void search_serverError_throwsNon2xxException() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withServerError());

        assertThatThrownBy(() -> service.search(new VectorSearchRequest(VECTOR, 5)))
            .isInstanceOf(VectorSearchServiceException.class)
            .hasMessage(VectorSearchServiceException.NON_2XX_RESPONSE);

        server.verify();
    }

    @Test
    void search_clientError_throwsNon2xxException() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withBadRequest());

        assertThatThrownBy(() -> service.search(new VectorSearchRequest(VECTOR, 5)))
            .isInstanceOf(VectorSearchServiceException.class)
            .hasMessage(VectorSearchServiceException.NON_2XX_RESPONSE);

        server.verify();
    }

    @Test
    void search_emptyResultList_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    @Test
    void search_relativeThumbnailPath_isPrefixedWithPosterBaseUrl() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.9,
                      "payload": {
                        "movie_id": "gq",
                        "title": "Galaxy Quest",
                        "release_year": 1999,
                        "genres": ["Science Fiction"],
                        "summary_snippet": "By Grabthar's hammer.",
                        "thumbnail_url": "/poster123.jpg"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo(POSTER_BASE_URL + "/poster123.jpg");

        server.verify();
    }

    @Test
    void search_relativeThumbnailWithoutLeadingSlash_isStillJoinedCleanly() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.9,
                      "payload": {
                        "movie_id": "bp",
                        "title": "Bare Path",
                        "release_year": 2010,
                        "genres": ["Drama"],
                        "summary_snippet": "x",
                        "thumbnail_url": "abc.jpg"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo(POSTER_BASE_URL + "/abc.jpg");

        server.verify();
    }

    @Test
    void search_absoluteThumbnailUrl_isReturnedUnchanged() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.9,
                      "payload": {
                        "movie_id": "aa",
                        "title": "Already Absolute",
                        "release_year": 2020,
                        "genres": ["Drama"],
                        "summary_snippet": "x",
                        "thumbnail_url": "https://other.example/img.jpg"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo("https://other.example/img.jpg");

        server.verify();
    }

    @Test
    void search_posterBaseUrlWithTrailingSlash_isNormalized() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(1);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL + "/");
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.9,
                      "payload": {
                        "movie_id": "ts",
                        "title": "Trailing Slash",
                        "release_year": 2010,
                        "genres": ["Drama"],
                        "summary_snippet": "x",
                        "thumbnail_url": "/p.jpg"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = localService.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo(POSTER_BASE_URL + "/p.jpg");

        server.verify();
    }

    @Test
    void search_sendsWithPayloadTrue() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.with_payload").value(true))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 3));

        server.verify();
    }

    // ── multi-chunk aggregation tests ──

    @Test
    void search_multiChunk_returnsDistinctMoviesSortedByMeanScoreDescending() {
        // movie "111" has 2 chunks (scores 0.90 + 0.70) → mean 0.80
        // movie "222" has 1 chunk (score 0.85) → mean 0.85
        // expected order: "Alien" (0.85) first, "Cast Away" (0.80) second
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(2))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He crash-lands on an island.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He builds a raft.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space no one hears you.",
                      "full_summary": "Full Alien summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");
        assertThat(response.getResults().get(1).getTitle()).isEqualTo("Cast Away");

        server.verify();
    }

    @Test
    void search_twoChunksSameMovie_meanScoreComputed() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He crash-lands on an island.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He builds a raft.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }

    @Test
    void search_matchingSegmentFromChunkText() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He crash-lands on an island.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He builds a raft.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment())
            .isEqualTo("He crash-lands on an island.");

        server.verify();
    }

    @Test
    void search_matchingSegmentFallsBackToSummarySnippetWhenChunkTextNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.85, "payload": {"movie_id": "old-1", "title": "Old Movie",
                      "release_year": 2010, "genres": ["Drama"],
                      "summary_snippet": "Old schema snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment())
            .isEqualTo("Old schema snippet.");

        server.verify();
    }

    @Test
    void search_summarySnippetFromFullSummary() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.85, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He crash-lands.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet())
            .isEqualTo("Full Cast Away summary.");

        server.verify();
    }

    @Test
    void search_summarySnippetFallsBackToSummarySnippetWhenFullSummaryNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.85, "payload": {"movie_id": "old-1", "title": "Old Movie",
                      "release_year": 2010, "genres": ["Drama"],
                      "summary_snippet": "Old schema snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet())
            .isEqualTo("Old schema snippet.");

        server.verify();
    }

    @Test
    void search_nullMovieId_chunkSkipped() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": null, "title": "Ghost Movie",
                      "release_year": 2020, "genres": ["Horror"],
                      "summary_snippet": "Should be skipped.", "thumbnail_url": null}},
                    {"score": 0.80, "payload": {"movie_id": "valid-1", "title": "Valid Movie",
                      "release_year": 2021, "genres": ["Drama"],
                      "summary_snippet": "Valid snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Valid Movie");

        server.verify();
    }

    @Test
    void search_nullPayload_chunkSkipped() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": null},
                    {"score": 0.80, "payload": {"movie_id": "valid-1", "title": "Valid Movie",
                      "release_year": 2021, "genres": ["Drama"],
                      "summary_snippet": "Valid snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Valid Movie");

        server.verify();
    }

    @Test
    void search_nullResponseBody_returnsEmpty() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    @Test
    void search_nullResultArray_returnsEmpty() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                { "result": null }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    @Test
    void search_resultCountLimitedByRequestLimit() {
        // 3 distinct movies in pool, but limit=2
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(2))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "m1", "title": "Movie 1",
                      "release_year": 2001, "genres": ["Action"],
                      "summary_snippet": "s1", "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "m2", "title": "Movie 2",
                      "release_year": 2002, "genres": ["Drama"],
                      "summary_snippet": "s2", "thumbnail_url": null}},
                    {"score": 0.80, "payload": {"movie_id": "m3", "title": "Movie 3",
                      "release_year": 2003, "genres": ["Comedy"],
                      "summary_snippet": "s3", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);

        server.verify();
    }

    // ── oversampling factor tests ──

    @Test
    void search_oversamplingFactorSentToQdrant() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer localServer = MockRestServiceServer.createServer(rt);
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(5);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(rt, props, tmdb);

        localServer.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(10))  // 2 * 5
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 2));

        localServer.verify();
    }

    @Test
    void search_oversamplingFactorDefaultIs20() {
        QdrantProperties props = new QdrantProperties();
        assertThat(props.getOversamplingFactor()).isEqualTo(20);
    }

    @Test
    void search_oversamplingFactorClampedWhenTooHigh() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer localServer = MockRestServiceServer.createServer(rt);
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(150);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(rt, props, tmdb);

        localServer.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100))  // 5 * 20 (clamped)
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        localServer.verify();
    }

    @Test
    void search_oversamplingFactorClampedWhenZero() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer localServer = MockRestServiceServer.createServer(rt);
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(0);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(rt, props, tmdb);

        localServer.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100))  // 5 * 20 (clamped)
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        localServer.verify();
    }

    @Test
    void search_oversamplingFactorClampedWhenNegative() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer localServer = MockRestServiceServer.createServer(rt);
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(-1);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(rt, props, tmdb);

        localServer.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100))  // 5 * 20 (clamped)
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        localServer.verify();
    }
}
