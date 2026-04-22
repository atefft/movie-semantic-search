package com.moviesearch.model;

import lombok.Value;

import java.util.List;

@Value
public class VectorSearchResponse {
    List<MovieResult> results;
}
