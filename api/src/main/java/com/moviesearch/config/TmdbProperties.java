package com.moviesearch.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tmdb")
public class TmdbProperties {

    private String posterBaseUrl;

    @NotNull
    private String apiKey;

    public String getPosterBaseUrl() { return posterBaseUrl; }
    public void setPosterBaseUrl(String posterBaseUrl) { this.posterBaseUrl = posterBaseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
