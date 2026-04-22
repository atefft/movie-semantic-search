package com.moviesearch.service.impl;

import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import com.moviesearch.model.SearchRequest;
import com.moviesearch.model.SearchResponse;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import com.moviesearch.service.EmbeddingService;
import com.moviesearch.service.SearchService;
import com.moviesearch.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MockSearchService implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(MockSearchService.class);
    private static final int MAX_QUERY_LENGTH = 500;

    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;

    public MockSearchService(EmbeddingService embeddingService, VectorSearchService vectorSearchService) {
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        if (request.getQuery().length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                "Query exceeds maximum length of " + MAX_QUERY_LENGTH + " characters");
        }

        log.info("SearchService.search called [query='{}', limit={}]",
            request.getQuery(), request.getLimit());

        EmbeddingResponse embedding = embeddingService.embed(new EmbeddingRequest(request.getQuery()));
        VectorSearchResponse vectorResults = vectorSearchService.search(
            new VectorSearchRequest(embedding.getVector(), request.getLimit())
        );

        int count = vectorResults.getResults().size();
        if (count == 0) {
            log.warn("Search returned 0 results [query='{}']. If this persists, verify the vector database collection is initialized and non-empty.",
                request.getQuery());
        }
        log.info("Search returning {} results [query='{}']", count, request.getQuery());

        return new SearchResponse(
            request.getQuery(),
            request.getLimit(),
            count,
            vectorResults.getResults()
        );
    }
}
