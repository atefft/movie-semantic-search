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
import org.springframework.http.HttpStatus;
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

    // ---- Happy path: 3 chunks, 2 movies, sorted by mean score ----

    @Test
    void search_threeChunksTwoMovies_returnsTwoResultsSortedByMeanScoreDesc() {
        // movie "111" (Cast Away): chunks with scores 0.90 and 0.70 → mean 0.80
        // movie "222" (Alien): chunk with score 0.85 → mean 0.85
        // Expected order: Alien (0.85), Cast Away (0.80)
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
        assertThat(first.getTitle()).isEqualTo("Alien");
        assertThat(first.getScore()).isEqualTo(0.85f);
        assertThat(second.getTitle()).isEqualTo("Cast Away");
        assertThat(second.getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }

    // ---- QdrantSearchRequest.limit = request.getLimit() * oversamplingFactor ----

    @Test
    void search_qdrantRequestLimit_equalsLimitTimesOversamplingFactor() {
        // default oversamplingFactor=20, limit=5 → expected Qdrant limit = 100
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(100))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        service.search(new VectorSearchRequest(VECTOR, 5));

        server.verify();
    }

    // ---- Mean score computation ----

    @Test
    void search_twoChunksSameMovie_scoreIsMeanOfBothChunkScores() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "Chunk A", "full_summary": "Summary.", "thumbnail_url": null}},
                    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "Chunk B", "full_summary": "Summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getScore()).isCloseTo(0.80f, within(0.001f));

        server.verify();
    }

    // ---- matchingSegment: chunk_text when non-null ----

    @Test
    void search_matchingSegment_usesChunkText_whenChunkTextNonNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": "He crash-lands on an island.",
                      "full_summary": "Full summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getMatchingSegment())
            .isEqualTo("He crash-lands on an island.");

        server.verify();
    }

    // ---- matchingSegment: falls back to summary_snippet when chunk_text is null ----

    @Test
    void search_matchingSegment_fallsBackToSummarySnippet_whenChunkTextNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "chunk_text": null,
                      "summary_snippet": "Old snippet for Cast Away.",
                      "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getMatchingSegment())
            .isEqualTo("Old snippet for Cast Away.");

        server.verify();
    }

    // ---- summarySnippet: full_summary when non-null ----

    @Test
    void search_summarySnippet_usesFullSummary_whenFullSummaryNonNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "full_summary": "Full Cast Away summary.",
                      "summary_snippet": "Old snippet.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getSummarySnippet())
            .isEqualTo("Full Cast Away summary.");

        server.verify();
    }

    // ---- summarySnippet: falls back to summary_snippet when full_summary is null ----

    @Test
    void search_summarySnippet_fallsBackToSummarySnippet_whenFullSummaryNull() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away",
                      "release_year": 2000, "genres": ["Drama"],
                      "full_summary": null,
                      "summary_snippet": "Old snippet for Cast Away.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 1));

        assertThat(response.getResults().get(0).getSummarySnippet())
            .isEqualTo("Old snippet for Cast Away.");

        server.verify();
    }

    // ---- Chunk with movie_id = null is excluded ----

    @Test
    void search_nullMovieIdChunk_isExcluded_otherResultsUnaffected() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": null, "title": "Ghost Movie",
                      "release_year": 2010, "genres": ["Comedy"], "thumbnail_url": null}},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space no one hears you.",
                      "full_summary": "Full Alien summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");

        server.verify();
    }

    // ---- Empty Qdrant result ----

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

    // ---- Exception handling ----

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

    // ---- Result count is bounded by request.getLimit() ----

    @Test
    void search_resultCountLimitedToRequestedLimit() {
        // Pool has 3 distinct movies but limit=2; only top 2 by mean score returned
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": {"movie_id": "1", "title": "A",
                      "release_year": 2000, "genres": [], "thumbnail_url": null}},
                    {"score": 0.90, "payload": {"movie_id": "2", "title": "B",
                      "release_year": 2001, "genres": [], "thumbnail_url": null}},
                    {"score": 0.80, "payload": {"movie_id": "3", "title": "C",
                      "release_year": 2002, "genres": [], "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 2));

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("A");
        assertThat(response.getResults().get(1).getTitle()).isEqualTo("B");

        server.verify();
    }

    // ---- oversamplingFactor clamping ----

    @Test
    void search_oversamplingFactorAbove100_clampsToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(150);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        // limit=2, effective factor=20 → Qdrant limit = 40
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_oversamplingFactorZero_clampsToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(0);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        // limit=2, effective factor=20 → Qdrant limit = 40
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    @Test
    void search_oversamplingFactorNegative_clampsToDefault20() {
        QdrantProperties props = new QdrantProperties();
        props.setBaseUrl(BASE_URL);
        props.setOversamplingFactor(-1);
        TmdbProperties tmdb = new TmdbProperties();
        tmdb.setPosterBaseUrl(POSTER_BASE_URL);
        VectorSearchServiceImpl localService = new VectorSearchServiceImpl(restTemplate, props, tmdb);

        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.limit").value(40))
            .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        localService.search(new VectorSearchRequest(VECTOR, 2));

        server.verify();
    }

    // ---- Null response body / null result field ----

    @Test
    void search_nullResultFieldInBody_returnsEmptyResponse() {
        // {} → QdrantSearchResponse with result=null
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    @Test
    void search_nullResponseBody_returnsEmptyResponse() {
        // 204 No Content → resp.getBody() is null
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).isEmpty();

        server.verify();
    }

    // ---- Null payload chunk is skipped ----

    @Test
    void search_nullPayloadChunk_isSkipped_otherChunksAggregated() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {"score": 0.95, "payload": null},
                    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien",
                      "release_year": 1979, "genres": ["Sci-Fi"],
                      "chunk_text": "In space no one hears you.",
                      "full_summary": "Full Alien summary.", "thumbnail_url": null}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        VectorSearchResponse response = service.search(new VectorSearchRequest(VECTOR, 5));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Alien");

        server.verify();
    }

    // ---- QdrantProperties: default oversamplingFactor is 20 ----

    @Test
    void qdrantProperties_defaultOversamplingFactor_is20() {
        QdrantProperties props = new QdrantProperties();
        assertThat(props.getOversamplingFactor()).isEqualTo(20);
    }

    // ---- Existing thumbnail URL tests (updated with movie_id) ----

    @Test
    void search_allFieldsMapped_oldSchemaFallback() {
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.with_payload").value(true))
            .andExpect(jsonPath("$.limit").value(100))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.92,
                      "payload": {
                        "movie_id": "1",
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
        assertThat(result.getMatchingSegment()).isEqualTo("A FedEx executive stranded on an island.");
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
    void search_multipleResults_allMapped() {
        server.expect(requestTo(SEARCH_URL))
            .andRespond(withSuccess("""
                {
                  "result": [
                    {
                      "score": 0.92,
                      "payload": {
                        "movie_id": "1",
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
                        "movie_id": "2",
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
}
