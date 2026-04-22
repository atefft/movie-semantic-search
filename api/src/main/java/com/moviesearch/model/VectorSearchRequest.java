package com.moviesearch.model;

import lombok.Value;

@Value
public class VectorSearchRequest {
    float[] vector;
    int limit;
}
