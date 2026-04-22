package com.moviesearch.exception;

public class EnrichWithTmdbServiceException extends RuntimeException {

    public static final String TMDB_UNAVAILABLE =
        "Enrich with TMDB service failed: TMDB API unavailable";
    public static final String ENRICHMENT_FAILED =
        "Enrich with TMDB service failed: enrichment error";

    public EnrichWithTmdbServiceException(String reason) {
        super(reason);
    }

    public EnrichWithTmdbServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
