package com.moviesearch.exception;

public class DownloadDatasetServiceException extends RuntimeException {

    public static final String DOWNLOAD_FAILED =
        "Download Dataset service failed: unable to fetch corpus";
    public static final String EXTRACTION_FAILED =
        "Download Dataset service failed: archive extraction error";

    public DownloadDatasetServiceException(String reason) {
        super(reason);
    }

    public DownloadDatasetServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
