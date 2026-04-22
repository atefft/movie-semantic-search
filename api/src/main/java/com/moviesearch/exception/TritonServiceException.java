package com.moviesearch.exception;

public class TritonServiceException extends RuntimeException {

    public static final String START_FAILED = "Triton service failed to start";
    public static final String STOP_FAILED = "Triton service failed to stop";
    public static final String CONNECTION_REFUSED = "Triton service unavailable: connection refused";

    public TritonServiceException(String reason) {
        super(reason);
    }

    public TritonServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
