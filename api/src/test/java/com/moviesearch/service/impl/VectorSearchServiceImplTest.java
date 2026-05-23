package com.moviesearch.service.impl;

import com.moviesearch.config.QdrantProperties;
import com.moviesearch.config.TmdbProperties;
import com.moviesearch.exception.VectorSearchServiceException;
import com.moviesearch.model.MovieResult;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceImplTest {

    private static final String BASE_URL = "http://qdrant-test:6333";
    private static final String SEARCH_URL = BASE_URL + "/collections/movies/points/search";
    private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w200";
    private static final float[] VECTOR = new float[]{0.1f, 0.2f, 0.3f};

    // HTTP-level test infrastructure
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private VectorSearchServiceImpl service;

    // Mockito-based infrastructure for edge cases requiring null bodies etc.
    @Mock
    private RestTemplate mockRestTemplate;
    private VectorSearchServiceImpl mockService;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);

        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);

        service = new VectorSearchServiceImpl(restTemplate, props, tmdb);
        mockService = new VectorSearchServiceImpl(mockRestTemplate, props, tmdb);
    }

    private VectorSearchServiceImpl serviceWithFactor(int factor) {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(factor);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        return new VectorSearchServiceImpl(restTemplate, props, tmdb);
    }

    // -------------------------------------------------------------------------
    // Happy path: multi-chunk aggregation
    // -------------------------------------------------------------------------

    @Test
    void search_happyPath_threeChunksTwoMovies_returnsTwoResultsSortedByMeanScore() {
        // limit=2, default factor=20 → Qdrant limit=40
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.with_payload").value(true))
            .andExpect(jsonPath("$.limit").value(2 * 20))
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

        // Alien: single chunk score = 0.85, sorted first
        MovieResult alien = response.getResults().get(0);
        assertThat(alien.getTitle()).isEqualTo("Alien");
        assertThat(alien.getScore()).isCloseTo(0.85f, within(0.001f));
        assertThat(alien.getMatchingSegment()).isEqualTo("In space no one hears you.");
        assertThat(alien.getSummarySnippet()).isEqualTo("Full Alien summary.");

        // Cast Away: mean of (0.90 + 0.70) / 2 = 0.80, sorted second
        MovieResult castAway = response.getResults().get(1);
        assertThat(castAway.getTitle()).isEqualTo("Cast Away");
        assertThat(castAway.getScore()).isCloseTo(0.80f, within(0.001f));
        assertThat(castAway.getMatchingSegment()).isEqualTo("He crash-lands on an island.");
        assertThat(castAway.getSummarySnippet()).isEqualTo("Full Cast Away summary.");

        server.verify();
    }

    @Test
    void search_twoChunks_scoreIsArithmeticMean() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "chunk1",
                      "full_summary": "summary", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "chunk2",
                      "full_summary": "summary", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));
    }

    @Test
    void search_matchingSegment_isHighestScoringChunkText() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "best chunk",
                      "full_summary": "full", "thumbnail_url": null}},
                    {"score": 0.50, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "worse chunk",
                      "full_summary": "full", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("best chunk");
    }

    @Test
    void search_summarySnippet_isFullSummaryFromPayload() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "a chunk",
                      "full_summary": "The complete full summary.",
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("The complete full summary.");
    }

    // -------------------------------------------------------------------------
    // Backwards-compat: old-schema fallback (no chunk_text / full_summary)
    // -------------------------------------------------------------------------

    @Test
    void search_matchingSegment_fallsBackToSummarySnippet_whenChunkTextNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.85, "payload": {"movie_id": "111", "title": "Old Movie",
                      "release_year": 1990, "genres": ["Drama"],
                      "summary_snippet": "Old snippet text.",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("Old snippet text.");
    }

    @Test
    void search_summarySnippet_fallsBackToSummarySnippet_whenFullSummaryNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.85, "payload": {"movie_id": "111", "title": "Old Movie",
                      "release_year": 1990, "genres": ["Drama"],
                      "summary_snippet": "Old snippet text.",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("Old snippet text.");
    }

    // -------------------------------------------------------------------------
    // Null / missing payload guards
    // -------------------------------------------------------------------------

    @Test
    void search_nullMovieId_chunkIsExcluded() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": null, "title": "Ghost",
                      "release_year": 2000, "genres": [], "chunk_text": "x", "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Real Movie",
                      "release_year": 2001, "genres": [], "chunk_text": "y",
                      "full_summary": "full", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Real Movie");
    }

    @Test
    void search_nullPayload_chunkSkipped() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": null},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Valid",
                      "release_year": 2001, "genres": [], "chunk_text": "y",
                      "full_summary": "full", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Valid");
    }

    @Test
    void search_nullResponseBody_returnsEmptyResponse() {
        when(mockRestTemplate.postForEntity(anyString(), any(), any()))
            .thenReturn(ResponseEntity.ok(null));

        VectorSearchResponse response = mockService.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();
    }

    @Test
    void search_nullResultField_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                { "result": null }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();
    }

    @Test
    void search_emptyResult_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();
        server.verify();
    }

    // -------------------------------------------------------------------------
    // Limit enforcement
    // -------------------------------------------------------------------------

    @Test
    void search_resultCount_doesNotExceedLimit() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": "1", "title": "A",
                      "release_year": 2000, "genres": [], "chunk_text": "a",
                      "full_summary": "fa", "thumbnail_url": null}},
                    {"score": 0.90, "payload": {"movie_id": "2", "title": "B",
                      "release_year": 2001, "genres": [], "chunk_text": "b",
                      "full_summary": "fb", "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "3", "title": "C",
                      "release_year": 2002, "genres": [], "chunk_text": "c",
                      "full_summary": "fc", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        // Request limit=2 but pool has 3 distinct movies
        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Oversampling factor clamping
    // -------------------------------------------------------------------------

    @Test
    void search_qdrantRequestLimit_isLimitTimesOversamplingFactor() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactorAbove100_clampedToDefault() {
        VectorSearchServiceImpl svc = serviceWithFactor(150);
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(2 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_oversamplingFactorZero_clampedToDefault() {
        VectorSearchServiceImpl svc = serviceWithFactor(0);
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(2 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_oversamplingFactorNegative_clampedToDefault() {
        VectorSearchServiceImpl svc = serviceWithFactor(-1);
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(2 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_oversamplingFactorOne_usedAsIs() {
        VectorSearchServiceImpl svc = serviceWithFactor(1);
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 1))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // Exception handling
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Poster URL handling
    // -------------------------------------------------------------------------

    @Test
    void search_relativeThumbnailPath_isPrefixedWithPosterBaseUrl() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "Galaxy Quest",
                      "release_year": 1999, "genres": [], "chunk_text": "x",
                      "full_summary": "full", "thumbnail_url": "/poster123.jpg"}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo(POSTER_BASE_URL + "/poster123.jpg");
        server.verify();
    }

    @Test
    void search_relativeThumbnailWithoutLeadingSlash_isJoinedCleanly() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "Bare Path",
                      "release_year": 2010, "genres": [], "chunk_text": "x",
                      "full_summary": "full", "thumbnail_url": "abc.jpg"}}
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
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "Already Absolute",
                      "release_year": 2020, "genres": [], "chunk_text": "x",
                      "full_summary": "full", "thumbnail_url": "https://other.example/img.jpg"}}
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
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL + "/");
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "Trailing Slash",
                      "release_year": 2010, "genres": [], "chunk_text": "x",
                      "full_summary": "full", "thumbnail_url": "/p.jpg"}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = localService.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo(POSTER_BASE_URL + "/p.jpg");
        server.verify();
    }

    @Test
    void search_nullThumbnailUrl_returnsNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "No Thumb",
                      "release_year": 2010, "genres": [], "chunk_text": "x",
                      "full_summary": "full", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl()).isNull();
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

    // -------------------------------------------------------------------------
    // QdrantProperties defaults and validation
    // -------------------------------------------------------------------------

    @Test
    void qdrantProperties_missingOversamplingFactor_defaultIs20() {
        QdrantProperties props = new QdrantProperties();
        assertThat(props.getOversamplingFactor()).isEqualTo(20);
    }

    @Test
    void qdrantProperties_blankBaseUrl_failsValidation() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl("");
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(props)).isNotEmpty();
    }
}
