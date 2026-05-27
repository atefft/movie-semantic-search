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
    // Multi-chunk aggregation (spec happy path)
    // -----------------------------------------------------------------------

    @Test
    void search_multiChunk_twoMoviesThreeChunks_returnsTwoResultsSortedByMeanScore() {
        // movie "111" has 2 chunks (scores 0.90, 0.70 → mean 0.80)
        // movie "222" has 1 chunk  (score  0.85           → mean 0.85)
        // sorted desc: "222" (0.85), "111" (0.80)
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
        // Alien ranked first (mean 0.85 > 0.80)
        MovieResult alien = response.getResults().get(0);
        assertThat(alien.getTitle()).isEqualTo("Alien");
        assertThat(alien.getScore()).isEqualTo(0.85f);
        assertThat(alien.getMatchingSegment()).isEqualTo("In space no one hears you.");
        assertThat(alien.getSummarySnippet()).isEqualTo("Full Alien summary.");

        MovieResult castAway = response.getResults().get(1);
        assertThat(castAway.getTitle()).isEqualTo("Cast Away");
        assertThat(castAway.getScore()).isCloseTo(0.80f, within(0.001f));
        assertThat(castAway.getMatchingSegment()).isEqualTo("He crash-lands on an island.");
        assertThat(castAway.getSummarySnippet()).isEqualTo("Full Cast Away summary.");

        server.verify();
    }

    @Test
    void search_qdrantRequestLimit_equalsRequestLimitTimesOversamplingFactor() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(5 * 20))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    @Test
    void search_twoChunksSameMovie_scoreIsArithmeticMean() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "Chunk one.", "full_summary": "Full summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "Chunk two.", "full_summary": "Full summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));
    }

    @Test
    void search_matchingSegment_isChunkTextOfHighestScoringChunk() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "Best chunk.", "full_summary": "Full.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "Lower chunk.", "full_summary": "Full.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("Best chunk.");
    }

    @Test
    void search_oldSchema_matchingSegmentFallsBackToSummarySnippet() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.80, "payload": {"movie_id": "111", "title": "Old Movie",
                      "release_year": 2000, "genres": [],
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getMatchingSegment()).isEqualTo("Old snippet.");
    }

    @Test
    void search_newSchema_summarySnippetIsFullSummary() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.80, "payload": {"movie_id": "111", "title": "Movie",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "A chunk.", "full_summary": "The full summary text.",
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("The full summary text.");
    }

    @Test
    void search_oldSchema_summarySnippetFallsBackToSummarySnippetField() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.80, "payload": {"movie_id": "111", "title": "Old Movie",
                      "release_year": 2000, "genres": [],
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults().get(0).getSummarySnippet()).isEqualTo("Old snippet.");
    }

    @Test
    void search_chunkWithNullMovieId_isExcluded() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": null, "title": "Ghost Movie",
                      "release_year": 2010, "genres": [], "thumbnail_url": null}},
                    {"score": 0.80, "payload": {"movie_id": "222", "title": "Real Movie",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "Real chunk.", "full_summary": "Real summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Real Movie");
    }

    @Test
    void search_resultCountCappedAtRequestLimit() {
        // 3 distinct movies returned, but limit=2
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "A", "title": "Alpha",
                      "release_year": 2001, "genres": [], "chunk_text": "c", "full_summary": "f", "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "B", "title": "Beta",
                      "release_year": 2002, "genres": [], "chunk_text": "c", "full_summary": "f", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "C", "title": "Gamma",
                      "release_year": 2003, "genres": [], "chunk_text": "c", "full_summary": "f", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // Oversampling factor clamping
    // -----------------------------------------------------------------------

    @Test
    void search_oversamplingFactorAbove100_isClampedToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(150);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(3 * 20))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 3));

        server.verify();
    }

    @Test
    void search_oversamplingFactorZero_isClampedToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(0);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(3 * 20))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 3));

        server.verify();
    }

    @Test
    void search_oversamplingFactorNegative_isClampedToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(-1);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(3 * 20))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 3));

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
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    // -----------------------------------------------------------------------
    // Null safety
    // -----------------------------------------------------------------------

    @Test
    void search_nullResponseBody_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();
    }

    @Test
    void search_nullResultField_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("{\"result\":null}", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();
    }

    @Test
    void search_chunkWithNullPayload_isSkippedSilently() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": null},
                    {"score": 0.80, "payload": {"movie_id": "222", "title": "Real Movie",
                      "release_year": 2000, "genres": [],
                      "chunk_text": "Real chunk.", "full_summary": "Real summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Real Movie");
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

    @Test
    void search_emptyResultList_returnsEmptyResponse() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

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
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 3));

        server.verify();
    }
}
