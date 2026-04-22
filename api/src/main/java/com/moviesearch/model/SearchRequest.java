package com.moviesearch.model;

import lombok.Value;

@Value
public class SearchRequest {
    String query;
    int limit;
}
