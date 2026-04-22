package com.moviesearch.exception;

public class ExportModelServiceException extends RuntimeException {

    public static final String EXPORT_FAILED =
        "Export Model service failed: unable to export model";
    public static final String QUANTIZATION_FAILED =
        "Export Model service failed: quantization error";

    public ExportModelServiceException(String reason) {
        super(reason);
    }

    public ExportModelServiceException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
