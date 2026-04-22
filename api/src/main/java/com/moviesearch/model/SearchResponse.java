package com.moviesearch.model;

import lombok.Value;

import java.util.List;

@Value
public class SearchResponse {
    String query;
    int requestedLimit;
    int count;
    List<MovieResult> results;
}
