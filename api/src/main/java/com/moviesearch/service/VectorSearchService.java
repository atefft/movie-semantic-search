package com.moviesearch.service;

import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;

public interface VectorSearchService {
    VectorSearchResponse search(VectorSearchRequest request);
}
