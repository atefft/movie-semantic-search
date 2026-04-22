package com.moviesearch.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class MovieResult {
    String title;
    Integer year;
    List<String> genres;
    float score;
    String summarySnippet;
    String thumbnailUrl;
}
