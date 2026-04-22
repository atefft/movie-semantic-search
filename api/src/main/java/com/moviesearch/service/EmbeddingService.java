package com.moviesearch.service;

import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;

public interface EmbeddingService {
    EmbeddingResponse embed(EmbeddingRequest request);
}
