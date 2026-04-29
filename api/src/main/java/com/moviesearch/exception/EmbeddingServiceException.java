package com.moviesearch.exception;

public class EmbeddingServiceException extends RuntimeException {

    public static final String CONNECTION_REFUSED =
        "Embedding service unavailable: connection refused";
    public static final String INVALID_REQUEST =
        "Embedding service rejected request: invalid input";

    public static String invalidVectorDimension(int actual) {
        return "Embedding service returned unexpected vector dimension: " + actual;
    }

    public EmbeddingServiceException(String reason) {
        super(reason);
    }

    public EmbeddingServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
