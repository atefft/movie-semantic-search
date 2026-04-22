package com.moviesearch.service;

import com.moviesearch.model.SearchRequest;
import com.moviesearch.model.SearchResponse;

public interface SearchService {
    SearchResponse search(SearchRequest request);
}
