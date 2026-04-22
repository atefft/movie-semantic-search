package com.moviesearch.controller;

import com.moviesearch.exception.EmbeddingServiceException;
import com.moviesearch.exception.VectorSearchServiceException;
import com.moviesearch.model.SearchRequest;
import com.moviesearch.model.SearchResponse;
import com.moviesearch.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchService searchService;

    // --- Happy paths ---

    @Test
    void search_happyPath() throws Exception {
        SearchResponse mockResponse = new SearchResponse("survival drama", 10, 2, List.of());
        when(searchService.search(argThat(r -> "survival drama".equals(r.getQuery()))))
            .thenReturn(mockResponse);

        mockMvc.perform(get("/api/search").param("q", "survival drama"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.query").value("survival drama"))
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.requestedLimit").value(10));
    }

    @Test
    void search_defaultLimit() throws Exception {
        SearchResponse mockResponse = new SearchResponse("foo", 10, 0, List.of());
        when(searchService.search(argThat(r -> r.getLimit() == 10))).thenReturn(mockResponse);

        mockMvc.perform(get("/api/search").param("q", "foo"))
            .andExpect(status().isOk());

        verify(searchService).search(argThat(r -> r.getLimit() == 10));
    }

    @Test
    void search_customLimit() throws Exception {
        SearchResponse mockResponse = new SearchResponse("foo", 3, 0, List.of());
        when(searchService.search(argThat(r -> r.getLimit() == 3))).thenReturn(mockResponse);

        mockMvc.perform(get("/api/search").param("q", "foo").param("limit", "3"))
            .andExpect(status().isOk());

        verify(searchService).search(argThat(r -> r.getLimit() == 3));
    }

    @Test
    void search_limitCappedAt50() throws Exception {
        SearchResponse mockResponse = new SearchResponse("foo", 50, 0, List.of());
        when(searchService.search(argThat(r -> r.getLimit() == 50))).thenReturn(mockResponse);

        mockMvc.perform(get("/api/search").param("q", "foo").param("limit", "100"))
            .andExpect(status().isOk());

        verify(searchService).search(argThat(r -> r.getLimit() == 50));
    }

    @Test
    void search_unicodeQuery() throws Exception {
        SearchResponse mockResponse = new SearchResponse("宇宙", 10, 0, List.of());
        when(searchService.search(argThat(r -> "宇宙".equals(r.getQuery())))).thenReturn(mockResponse);

        mockMvc.perform(get("/api/search").param("q", "宇宙"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.query").value("宇宙"));
    }

    // --- Validation edge cases → 400 ---

    @Test
    void search_blankQuery() throws Exception {
        mockMvc.perform(get("/api/search").param("q", ""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Query parameter 'q' must not be blank"));
    }

    @Test
    void search_whitespaceOnlyQuery() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Query parameter 'q' must not be blank"));
    }

    @Test
    void search_missingQParam() throws Exception {
        mockMvc.perform(get("/api/search"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void search_queryTooLong() throws Exception {
        String longQuery = "a".repeat(501);

        mockMvc.perform(get("/api/search").param("q", longQuery))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(
                "Query parameter 'q' exceeds maximum length of 500 characters"));
    }

    @Test
    void search_negativeLimit() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "foo").param("limit", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Limit must be at least 1"));
    }

    @Test
    void search_zeroLimit() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "foo").param("limit", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Limit must be at least 1"));
    }

    // --- Service failure edge cases → 503/500 ---

    @Test
    void search_embeddingServiceConnectionRefused() throws Exception {
        when(searchService.search(argThat(r -> "foo".equals(r.getQuery()))))
            .thenThrow(new EmbeddingServiceException(EmbeddingServiceException.CONNECTION_REFUSED));

        mockMvc.perform(get("/api/search").param("q", "foo"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value(
                "Embedding service unavailable: connection refused"));
    }

    @Test
    void search_vectorSearchConnectionRefused() throws Exception {
        when(searchService.search(argThat(r -> "foo".equals(r.getQuery()))))
            .thenThrow(new VectorSearchServiceException(VectorSearchServiceException.CONNECTION_REFUSED));

        mockMvc.perform(get("/api/search").param("q", "foo"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value(
                "Vector search service unavailable: connection refused"));
    }

    @Test
    void search_unexpectedRuntimeException() throws Exception {
        when(searchService.search(argThat(r -> "foo".equals(r.getQuery()))))
            .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/search").param("q", "foo"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("boom"));
    }
}
