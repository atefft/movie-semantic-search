package com.moviesearch.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PipelineStep {
    DOWNLOAD_DATASET(1, "Download Dataset"),
    EXPORT_MODEL(2, "Export Model"),
    GENERATE_EMBEDDINGS(3, "Generate Embeddings"),
    INGEST_INTO_QDRANT(4, "Ingest into Qdrant"),
    ENRICH_WITH_TMDB(5, "Enrich with TMDB");

    private final int number;
    private final String displayName;

    PipelineStep(int number, String displayName) {
        this.number = number;
        this.displayName = displayName;
    }

    @JsonValue
    public int getNumber() { return number; }

    public String getDisplayName() { return displayName; }

    public static PipelineStep fromNumber(int n) {
        for (PipelineStep step : values()) {
            if (step.number == n) return step;
        }
        throw new IllegalArgumentException("No PipelineStep with number: " + n);
    }
}
