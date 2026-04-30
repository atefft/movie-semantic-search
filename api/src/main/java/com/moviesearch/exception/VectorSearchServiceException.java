package com.moviesearch.exception;

public class VectorSearchServiceException extends RuntimeException {

    public static final String CONNECTION_REFUSED =
        "Vector search service unavailable: connection refused";
    public static final String NON_2XX_RESPONSE =
        "Vector search service returned an error response";
    public static final String INVALID_VECTOR =
        "Vector search service rejected request: invalid query vector";
    public static final String EMPTY_COLLECTION =
        "Vector search service connected but collection is empty: database not initialized";

    public VectorSearchServiceException(String reason) {
        super(reason);
    }

    public VectorSearchServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
