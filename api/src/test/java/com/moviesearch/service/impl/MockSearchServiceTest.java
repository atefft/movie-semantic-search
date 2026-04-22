package com.moviesearch.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.moviesearch.exception.EmbeddingServiceException;
import com.moviesearch.exception.VectorSearchServiceException;
import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import com.moviesearch.model.MovieResult;
import com.moviesearch.model.SearchRequest;
import com.moviesearch.model.SearchResponse;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import com.moviesearch.service.EmbeddingService;
import com.moviesearch.service.VectorSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockSearchServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private VectorSearchService vectorSearchService;

    @InjectMocks
    private MockSearchService searchService;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(MockSearchService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    private List<String> logMessages() {
        return logAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static List<MovieResult> makeResults(int count) {
        return List.of(
            MovieResult.builder().title("Film A").year(2020).genres(List.of()).score(0.9f)
                .summarySnippet("").thumbnailUrl(null).build(),
            MovieResult.builder().title("Film B").year(2021).genres(List.of()).score(0.8f)
                .summarySnippet("").thumbnailUrl(null).build(),
            MovieResult.builder().title("Film C").year(2022).genres(List.of()).score(0.7f)
                .summarySnippet("").thumbnailUrl(null).build()
        ).subList(0, count);
    }

    // --- Happy paths ---

    @Test
    void search_happyPath() {
        float[] vector = new float[384];
        when(embeddingService.embed(any())).thenReturn(new EmbeddingResponse(vector));
        when(vectorSearchService.search(any())).thenReturn(
            new VectorSearchResponse(makeResults(3)));

        SearchResponse response = searchService.search(new SearchRequest("space survival", 10));

        verify(embeddingService).embed(new EmbeddingRequest("space survival"));
        verify(vectorSearchService).search(new VectorSearchRequest(vector, 10));

        assertThat(response.getQuery()).isEqualTo("space survival");
        assertThat(response.getRequestedLimit()).isEqualTo(10);
        assertThat(response.getCount()).isEqualTo(3);
        assertThat(response.getResults()).hasSize(3);

        assertThat(logMessages()).contains(
            "SearchService.search called [query='space survival', limit=10]",
            "Search returning 3 results [query='space survival']"
        );
    }

    @Test
    void search_partialResults() {
        float[] vector = new float[384];
        when(embeddingService.embed(any())).thenReturn(new EmbeddingResponse(vector));
        when(vectorSearchService.search(any())).thenReturn(
            new VectorSearchResponse(makeResults(3)));

        SearchResponse response = searchService.search(new SearchRequest("space survival", 10));

        assertThat(response.getCount()).isEqualTo(3);
        assertThat(response.getRequestedLimit()).isEqualTo(10);
    }

    @Test
    void search_zeroResults() {
        when(embeddingService.embed(any())).thenReturn(new EmbeddingResponse(new float[384]));
        when(vectorSearchService.search(any())).thenReturn(new VectorSearchResponse(List.of()));

        SearchResponse response = searchService.search(new SearchRequest("space survival", 10));

        assertThat(response.getCount()).isEqualTo(0);
        assertThat(logMessages()).contains(
            "Search returned 0 results [query='space survival']. If this persists, verify the vector database collection is initialized and non-empty."
        );
    }

    // --- Edge cases ---

    @Test
    void search_embeddingThrows() {
        when(embeddingService.embed(any()))
            .thenThrow(new EmbeddingServiceException(EmbeddingServiceException.CONNECTION_REFUSED));

        assertThatThrownBy(() -> searchService.search(new SearchRequest("space survival", 10)))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.CONNECTION_REFUSED);
    }

    @Test
    void search_vectorSearchThrows() {
        when(embeddingService.embed(any())).thenReturn(new EmbeddingResponse(new float[384]));
        when(vectorSearchService.search(any()))
            .thenThrow(new VectorSearchServiceException(VectorSearchServiceException.CONNECTION_REFUSED));

        assertThatThrownBy(() -> searchService.search(new SearchRequest("space survival", 10)))
            .isInstanceOf(VectorSearchServiceException.class)
            .hasMessage(VectorSearchServiceException.CONNECTION_REFUSED);
    }

    @Test
    void search_queryTooLong() {
        String longQuery = "x".repeat(501);

        assertThatThrownBy(() -> searchService.search(new SearchRequest(longQuery, 10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Query exceeds maximum length of 500 characters");
    }
}
