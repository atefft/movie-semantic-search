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

    // -----------------------------------------------------------------------
    // Happy path — multi-chunk aggregation
    // -----------------------------------------------------------------------

    @Test
    void search_happyPath_twoMoviesThreeChunks_returnsTwoResultsSortedByMeanScore() {
        // movie "111" has 2 chunks (scores 0.90 + 0.70 → mean 0.80)
        // movie "222" has 1 chunk (score 0.85 → mean 0.85)
        // expected order: Alien (0.85) then Cast Away (0.80)
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.with_payload").value(true))
            .andExpect(jsonPath("$.limit").value(2 * 20))  // limit * default oversamplingFactor
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He crash-lands on an island.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space no one hears you.",
                      "full_summary": "Full Alien summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He builds a raft.",
                      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);
        MovieResult alien = response.getResults().get(0);
        MovieResult castAway = response.getResults().get(1);

        assertThat(alien.getTitle()).isEqualTo("Alien");
        assertThat(alien.getScore()).isCloseTo(0.85f, within(0.001f));
        assertThat(alien.getMatchingSegment()).isEqualTo("In space no one hears you.");
        assertThat(alien.getSummarySnippet()).isEqualTo("Full Alien summary.");

        assertThat(castAway.getTitle()).isEqualTo("Cast Away");
        assertThat(castAway.getScore()).isCloseTo(0.80f, within(0.001f));
        assertThat(castAway.getMatchingSegment()).isEqualTo("He crash-lands on an island.");
        assertThat(castAway.getSummarySnippet()).isEqualTo("Full Cast Away summary.");

        server.verify();
    }

    @Test
    void search_qdrantLimitIsLimitTimesOversamplingFactor() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_movieWithTwoChunks_scoreIsMeanOfBothChunks() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }

    @Test
    void search_matchingSegment_isChunkTextOfHighestScoringChunk() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "Best chunk.",
                      "full_summary": "Full.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [], "chunk_text": "Lower chunk.",
                      "full_summary": "Full.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("Best chunk.");

        server.verify();
    }

    @Test
    void search_summarySnippet_isFullSummaryWhenPresent() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "A chunk.", "full_summary": "The full summary.",
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("The full summary.");

        server.verify();
    }

    // -----------------------------------------------------------------------
    // Backwards-compat (old-schema) fallbacks
    // -----------------------------------------------------------------------

    @Test
    void search_oldSchema_matchingSegmentFallsBackToSummarySnippet() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "111", "title": "Old Movie",
                      "release_year": 2000, "genres": [],
                      "summary_snippet": "Old snippet only.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        MovieResult r = response.getResults().get(0);
        assertThat(r.getMatchingSegment()).isEqualTo("Old snippet only.");
        assertThat(r.getSummarySnippet()).isEqualTo("Old snippet only.");

        server.verify();
    }

    @Test
    void search_oldSchema_singlePointPerMovie_meanOfOneScoreEqualsScore() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.77, "payload": {"movie_id": "111", "title": "Old Movie",
                      "release_year": 2001, "genres": [],
                      "summary_snippet": "Snippet.", "thumbnail_url": null}},
                    {"score": 0.65, "payload": {"movie_id": "222", "title": "Other Old",
                      "release_year": 2002, "genres": [],
                      "summary_snippet": "Snippet2.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.77f, within(0.001f));
        assertThat(response.getResults().get(1).getScore()).isCloseTo(0.65f, within(0.001f));

        server.verify();
    }

    // -----------------------------------------------------------------------
    // Null / missing data guards
    // -----------------------------------------------------------------------

    @Test
    void search_chunkWithNullMovieId_isExcludedSilently() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": null, "title": "Ghost",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.80, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": [],
                      "summary_snippet": "In space.", "thumbnail_url": null}}
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
    void search_nullResponseBody_returnsEmptyResponseWithoutException() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    @Test
    void search_nullResultFieldInBody_returnsEmptyResponseWithoutException() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                { "result": null }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    @Test
    void search_chunkWithNullPayload_isSkippedSilently() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": null},
                    {"score": 0.80, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": [],
                      "summary_snippet": "In space.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");

        server.verify();
    }

    // -----------------------------------------------------------------------
    // oversamplingFactor clamping
    // -----------------------------------------------------------------------

    @Test
    void search_oversamplingFactorAbove100_clampedToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(150);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactorZero_clampedToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(0);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactorNegative_clampedToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(-1);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 20))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_oversamplingFactor1_fetchesLimitTimes1() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(1);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 1))
            .andRespond(withSuccess("""
                { "result": [] }
                """, MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Result count cap
    // -----------------------------------------------------------------------

    @Test
    void search_resultCountIsAtMostRequestedLimit() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "A", "release_year": 2001,
                      "genres": [], "summary_snippet": "s", "thumbnail_url": null}},
                    {"score": 0.8, "payload": {"movie_id": "2", "title": "B", "release_year": 2002,
                      "genres": [], "summary_snippet": "s", "thumbnail_url": null}},
                    {"score": 0.7, "payload": {"movie_id": "3", "title": "C", "release_year": 2003,
                      "genres": [], "summary_snippet": "s", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);

        server.verify();
    }

    // -----------------------------------------------------------------------
    // Thumbnail URL handling
    // -----------------------------------------------------------------------

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
    void search_relativeThumbnailWithoutLeadingSlash_isStillJoinedCleanly() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.9, "payload": {"movie_id": "1", "title": "Bare Path",
                      "release_year": 2010, "genres": ["Drama"],
                      "summary_snippet": "x", "thumbnail_url": "abc.jpg"}}
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
                      "release_year": 2020, "genres": ["Drama"],
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
                      "release_year": 2010, "genres": ["Drama"],
                      "summary_snippet": "x", "thumbnail_url": "/p.jpg"}}
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
}
