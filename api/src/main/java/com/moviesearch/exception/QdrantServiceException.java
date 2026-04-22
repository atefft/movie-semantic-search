package com.moviesearch.exception;

public class QdrantServiceException extends RuntimeException {

    public static final String START_FAILED = "Qdrant service failed to start";
    public static final String STOP_FAILED = "Qdrant service failed to stop";
    public static final String CONNECTION_REFUSED = "Qdrant service unavailable: connection refused";

    public QdrantServiceException(String reason) {
        super(reason);
    }

    public QdrantServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
