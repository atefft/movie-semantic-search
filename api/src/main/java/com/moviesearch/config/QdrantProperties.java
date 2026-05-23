package com.moviesearch.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {

    @NotBlank
    private String baseUrl = "http://localhost:6333";
    private boolean mock = false;
    private int oversamplingFactor = 20;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
    public int getOversamplingFactor() { return oversamplingFactor; }
    public void setOversamplingFactor(int oversamplingFactor) { this.oversamplingFactor = oversamplingFactor; }
}
