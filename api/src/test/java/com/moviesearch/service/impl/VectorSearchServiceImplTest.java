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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
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
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        service = new VectorSearchServiceImpl(restTemplate, props, tmdb);
    }

    // --- Multi-chunk aggregation and mean-pooling ---

    @Test
    void search_happyPath_multiChunk_twoMovies_sortedByMeanScoreDesc() {
        // 3 chunks: Cast Away (2 chunks: 0.90, 0.70) and Alien (1 chunk: 0.85)
        // Cast Away mean = 0.80, Alien mean = 0.85 → Alien ranks first
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
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
        MovieResult first = response.getResults().get(0);
        MovieResult second = response.getResults().get(1);
        assertThat(first.getTitle()).isEqualTo("Alien");
        assertThat(first.getScore()).isCloseTo(0.85f, within(0.001f));
        assertThat(second.getTitle()).isEqualTo("Cast Away");
        assertThat(second.getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }

    @Test
    void search_qdrantLimitIsRequestLimitTimesOversamplingFactor() {
        // Default oversamplingFactor=20, request limit=2 → Qdrant receives limit=40
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_twoChunkMovie_scoreIsArithmeticMean() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "chunk1",
                      "full_summary": "full", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "chunk2",
                      "full_summary": "full", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }

    @Test
    void search_matchingSegment_usesChunkText_whenNonNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "the chunk text",
                      "summary_snippet": "the snippet",
                      "full_summary": "full summary", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("the chunk text");

        server.verify();
    }

    @Test
    void search_matchingSegment_fallsBackToSummarySnippet_whenChunkTextNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [],
                      "summary_snippet": "old schema snippet",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("old schema snippet");

        server.verify();
    }

    @Test
    void search_summarySnippet_usesFullSummary_whenNonNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "chunk",
                      "full_summary": "the full summary",
                      "summary_snippet": "old snippet",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("the full summary");

        server.verify();
    }

    @Test
    void search_summarySnippet_fallsBackToSummarySnippet_whenFullSummaryNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [],
                      "summary_snippet": "old schema snippet",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("old schema snippet");

        server.verify();
    }

    // --- Null / missing field guards ---

    @Test
    void search_nullMovieId_chunkSkipped_otherResultsUnaffected() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": null, "title": "Ghost",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space.", "full_summary": "Full.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");

        server.verify();
    }

    @Test
    void search_nullPayload_chunkSkipped_otherChunksAggregated() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": null},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space.", "full_summary": "Full.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");

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
    void search_nullResponseBody_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    @Test
    void search_nullResultField_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    // --- Result count cap ---

    @Test
    void search_resultCountCappedAtRequestLimit_evenWhenPoolHasMoreDistinctMovies() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "A",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.8, "payload": {"movie_id": "2", "title": "B",
                      "release_year": 2001, "genres": [], "thumbnail_url": null}},
                    {"score": 0.7, "payload": {"movie_id": "3", "title": "C",
                      "release_year": 2002, "genres": [], "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);

        server.verify();
    }

    // --- Error handling ---

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

    // --- oversamplingFactor clamping ---

    @Test
    void search_oversamplingFactor_missingFromConfig_defaultIs20() {
        // A fresh QdrantProperties without setOversamplingFactor uses the field default of 20
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100)) // limit=5 * factor=20
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactor_greaterThan100_clampedTo20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(150);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100)) // limit=5 * clamped factor=20
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactor_zero_clampedTo20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(0);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100)) // limit=5 * clamped factor=20
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactor_negative_clampedTo20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(-1);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100)) // limit=5 * clamped factor=20
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactor_one_usedAsIs() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(1);
        TmdbProperties tmdb = new TmdbProperties();
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5)) // limit=5 * factor=1
            .andRespond(withSuccess("{ \"result\": [] }", MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    // --- Request format ---

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

    // --- Poster URL handling (backwards compat) ---

    @Test
    void search_nullThumbnailUrl_doesNotThrow() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.85, "payload": {"movie_id": "1", "title": "The Martian",
                      "release_year": 2015, "genres": ["Science Fiction"],
                      "summary_snippet": "An astronaut stranded on Mars.",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getThumbnailUrl()).isNull();

        server.verify();
    }

    @Test
    void search_relativeThumbnailPath_isPrefixedWithPosterBaseUrl() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "Galaxy Quest",
                      "release_year": 1999, "genres": ["Science Fiction"],
                      "summary_snippet": "By Grabthar's hammer.",
                      "thumbnail_url": "/poster123.jpg"}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo(POSTER_BASE_URL + "/poster123.jpg");

        server.verify();
    }

    @Test
    void search_absoluteThumbnailUrl_isReturnedUnchanged() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "Already Absolute",
                      "release_year": 2020, "genres": [],
                      "summary_snippet": "x",
                      "thumbnail_url": "https://other.example/img.jpg"}}
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
                      "release_year": 2010, "genres": [],
                      "summary_snippet": "x",
                      "thumbnail_url": "/p.jpg"}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = localService.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getThumbnailUrl())
            .isEqualTo(POSTER_BASE_URL + "/p.jpg");

        server.verify();
    }

    // --- QdrantProperties validation ---

    @Test
    void qdrantProperties_defaultOversamplingFactorIs20() {
        QdrantProperties props = new QdrantProperties();
        assertThat(props.getOversamplingFactor()).isEqualTo(20);
    }

    @Test
    void qdrantProperties_blankBaseUrl_failsValidation() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl("");
        jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
        jakarta.validation.Validator validator = factory.getValidator();
        java.util.Set<jakarta.validation.ConstraintViolation<QdrantProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
    }

    // --- All fields mapped (happy path fields) ---

    @Test
    void search_allFieldsMapped_singleResult() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.with_payload").value(true))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.92,
                      "payload": {
                        "movie_id": "42",
                        "title": "Cast Away",
                        "release_year": 2000,
                        "genres": ["Drama", "Adventure"],
                        "chunk_text": "He crash-lands on an island.",
                        "full_summary": "A FedEx executive stranded on an island.",
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
        assertThat(result.getMatchingSegment()).isEqualTo("He crash-lands on an island.");
        assertThat(result.getThumbnailUrl()).isEqualTo("https://example.com/cast-away.jpg");

        server.verify();
    }

    // --- Old-schema backwards compat (single point per movie, summary_snippet only) ---

    @Test
    void search_oldSchema_singlePointPerMovie_worksCorrectly() {
        // Old schema: one point per movie, summary_snippet only, no chunk_text/full_summary/movie_id
        // With movie_id present but chunk_text/full_summary absent
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.92, "payload": {"movie_id": "1", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "summary_snippet": "A FedEx executive stranded on an island.",
                      "thumbnail_url": null}},
                    {"score": 0.88, "payload": {"movie_id": "2", "title": "The Martian",
                      "release_year": 2015, "genres": ["Sci-Fi"],
                      "summary_snippet": "Astronaut stranded on Mars.",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        // Mean of single score = that score
        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.92f, within(0.001f));
        assertThat(response.getResults().get(0).getSummarySnippet())
            .isEqualTo("A FedEx executive stranded on an island.");
        assertThat(response.getResults().get(0).getMatchingSegment())
            .isEqualTo("A FedEx executive stranded on an island.");

        server.verify();
    }

    // --- matchingSegment = highest-scoring chunk_text when multiple chunks ---

    @Test
    void search_matchingSegment_isHighestScoringChunkText() {
        // movie 111 has 2 chunks; 0.90-scoring chunk comes first (Qdrant sorts desc)
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "He crash-lands on an island.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "He builds a raft.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getMatchingSegment())
            .isEqualTo("He crash-lands on an island.");

        server.verify();
    }

    // --- All-same-movie chunks collapse to one result ---

    @Test
    void search_allChunksSameMovie_returnsOneResult() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.8, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.7, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        // mean = (0.9 + 0.8 + 0.7) / 3 = 0.8
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }
}
