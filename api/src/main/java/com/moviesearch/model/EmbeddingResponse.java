package com.moviesearch.model;

import lombok.Value;

@Value
public class EmbeddingResponse {
    float[] vector;
}
