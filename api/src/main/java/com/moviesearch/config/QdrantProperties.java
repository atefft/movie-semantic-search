package com.moviesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {

    private String baseUrl = "http://localhost:6333";
    private boolean mock = false;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
}
