package com.moviesearch.controller;

import com.moviesearch.model.SearchRequest;
import com.moviesearch.model.SearchResponse;
import com.moviesearch.service.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SearchController {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_QUERY_LENGTH = 500;

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {

        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Query parameter 'q' must not be blank"));
        }

        if (q.length() > MAX_QUERY_LENGTH) {
            return ResponseEntity.badRequest().body(
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Query parameter 'q' exceeds maximum length of 500 characters"));
        }

        if (limit < MIN_LIMIT) {
            return ResponseEntity.badRequest().body(
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Limit must be at least 1"));
        }

        int effectiveLimit = Math.min(limit, MAX_LIMIT);
        SearchResponse response = searchService.search(new SearchRequest(q, effectiveLimit));
        return ResponseEntity.ok(response);
    }
}
