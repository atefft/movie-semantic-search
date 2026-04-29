package com.moviesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "triton")
public class TritonProperties {

    private String host = "localhost";
    private int port = 8001;
    private String modelName = "all-MiniLM-L6-v2";
    private long deadlineMs = 5000;
    private boolean mock = false;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public long getDeadlineMs() {
        return deadlineMs;
    }

    public void setDeadlineMs(long deadlineMs) {
        this.deadlineMs = deadlineMs;
    }

    public boolean isMock() {
        return mock;
    }

    public void setMock(boolean mock) {
        this.mock = mock;
    }
}
