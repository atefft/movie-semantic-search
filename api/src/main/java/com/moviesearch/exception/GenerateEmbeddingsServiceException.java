package com.moviesearch.exception;

public class GenerateEmbeddingsServiceException extends RuntimeException {

    public static final String TRITON_UNAVAILABLE =
        "Generate Embeddings service failed: Triton server unavailable";
    public static final String BATCH_FAILED =
        "Generate Embeddings service failed: batch processing error";

    public GenerateEmbeddingsServiceException(String reason) {
        super(reason);
    }

    public GenerateEmbeddingsServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
