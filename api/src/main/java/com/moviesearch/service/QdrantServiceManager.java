package com.moviesearch.service;

import com.moviesearch.model.ServiceStatus;

public interface QdrantServiceManager {
    void start();
    void stop();
    ServiceStatus getStatus();
}
