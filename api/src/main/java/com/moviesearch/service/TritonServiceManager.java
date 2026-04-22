package com.moviesearch.service;

import com.moviesearch.model.ServiceStatus;

public interface TritonServiceManager {
    void start();
    void stop();
    ServiceStatus getStatus();
}
