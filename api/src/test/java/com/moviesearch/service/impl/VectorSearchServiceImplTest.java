package com.moviesearch.service.impl;

import com.moviesearch.config.QdrantProperties;
import com.moviesearch.config.TmdbProperties;
import com.moviesearch.exception.VectorSearchServiceException;
import com.moviesearch.model.MovieResult;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    // --- Multi-chunk aggregation ---

    @Test
    void search_threeChunksTwoMovies_returnsTwoResultsSortedByMeanScoreDesc() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.with_payload").value(true))
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

        // Alien has mean 0.85, Cast Away has mean 0.80 → Alien ranked first
        assertThat(first.getTitle()).isEqualTo("Alien");
        assertThat(first.getScore()).isEqualTo(0.85f);
        assertThat(first.getMatchingSegment()).isEqualTo("In space no one hears you.");
        assertThat(first.getSummarySnippet()).isEqualTo("Full Alien summary.");

        assertThat(second.getTitle()).isEqualTo("Cast Away");
        assertThat(second.getScore()).isCloseTo(0.80f, within(0.001f));
        assertThat(second.getMatchingSegment()).isEqualTo("He crash-lands on an island.");
        assertThat(second.getSummarySnippet()).isEqualTo("Full Cast Away summary.");

        server.verify();
    }

    @Test
    void search_limitSentToQdrantIsLimitTimesOversamplingFactor() {
        // Default oversamplingFactor=20, request limit=2 → Qdrant limit=40
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("""
                    { "result": [] }
                    """, MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_twoChunksSameMovie_scoreIsMeanOfBothChunks() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie X",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "Chunk A", "full_summary": "Full summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Movie X",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "Chunk B", "full_summary": "Full summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }

    @Test
    void search_matchingSegmentUsesChunkTextWhenNonNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie X",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "The best chunk.", "full_summary": "Full summary.",
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("The best chunk.");

        server.verify();
    }

    @Test
    void search_matchingSegmentFallsBackToSummarySnippetWhenChunkTextIsNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie X",
                      "release_year": 2000, "genres": [],
                      "chunk_text": null, "full_summary": null,
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("Old snippet.");

        server.verify();
    }

    @Test
    void search_summarySnippetUsesFullSummaryWhenNonNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie X",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "A chunk.", "full_summary": "The complete summary.",
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("The complete summary.");

        server.verify();
    }

    @Test
    void search_summarySnippetFallsBackToSummarySnippetWhenFullSummaryIsNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie X",
                      "release_year": 2000, "genres": [],
                      "chunk_text": null, "full_summary": null,
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("Old snippet.");

        server.verify();
    }

    @Test
    void search_nullMovieId_chunkIsExcludedAndOtherResultsUnaffected() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": null, "title": "Ghost",
                      "release_year": 2020, "genres": [], "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space.", "full_summary": "Full summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");

        server.verify();
    }

    @Test
    void search_nullPayload_chunkIsSkippedSilently() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": null},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space.", "full_summary": "Full summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");

        server.verify();
    }

    @Test
    void search_resultCountDoesNotExceedLimit() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "1", "title": "A",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "2", "title": "B",
                      "release_year": 2001, "genres": [], "thumbnail_url": null}},
                    {"score": 0.80, "payload": {"movie_id": "3", "title": "C",
                      "release_year": 2002, "genres": [], "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);

        server.verify();
    }

    // --- Oversampling factor clamping ---

    @Test
    void search_oversamplingFactorOver100_clampsToDefault() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(150);
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, new TmdbProperties());

        // limit=2, factor clamped to 20 → Qdrant limit=40
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("""
                    { "result": [] }
                    """, MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_oversamplingFactorZero_clampsToDefault() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(0);
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, new TmdbProperties());

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("""
                    { "result": [] }
                    """, MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_oversamplingFactorNegative_clampsToDefault() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(-1);
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(restTemplate, props, new TmdbProperties());

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("""
                    { "result": [] }
                    """, MediaType.APPLICATION_JSON));

        svc.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    // --- Null guards ---

    @Test
    void search_nullResponseBody_returnsEmptyResponse() {
        RestTemplate mockRt = Mockito.mock(RestTemplate.class);
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl svc = new VectorSearchServiceImpl(mockRt, props, tmdb);

        Mockito.when(mockRt.postForEntity(eq(SEARCH_URL), any(), any()))
            .thenReturn(ResponseEntity.ok(null));

        VectorSearchResponse response = svc.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();
    }

    @Test
    void search_nullResultFieldInBody_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {}
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

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

    // --- Empty results ---

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

    // --- Thumbnail URL handling ---

    @Test
    void search_nullThumbnailUrl_doesNotThrow() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.85,
                      "payload": {
                        "movie_id": "1",
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
    void search_relativeThumbnailPath_isPrefixedWithPosterBaseUrl() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.9,
                      "payload": {
                        "movie_id": "1",
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
                        "movie_id": "1",
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
                        "movie_id": "1",
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
                        "movie_id": "1",
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

    // --- QdrantProperties unit tests ---

    @Test
    void qdrantProperties_defaultOversamplingFactorIs20() {
        QdrantProperties props = new QdrantProperties();
        assertThat(props.getOversamplingFactor()).isEqualTo(20);
    }

    @Test
    void qdrantProperties_blankBaseUrl_failsConstraintValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl("");
        Set<ConstraintViolation<QdrantProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
    }
}
