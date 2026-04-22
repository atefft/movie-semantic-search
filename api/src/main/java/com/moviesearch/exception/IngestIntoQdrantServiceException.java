package com.moviesearch.exception;

public class IngestIntoQdrantServiceException extends RuntimeException {

    public static final String CONNECTION_REFUSED =
        "Ingest into Qdrant service failed: connection refused";
    public static final String INGESTION_FAILED =
        "Ingest into Qdrant service failed: ingestion error";

    public IngestIntoQdrantServiceException(String reason) {
        super(reason);
    }

    public IngestIntoQdrantServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
