package com.moviesearch.service.impl;

import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.QdrantServiceManager;

public class MockQdrantServiceManager implements QdrantServiceManager {

    private volatile ServiceStatus status = ServiceStatus.STOPPED;

    @Override
    public void start() {
        status = ServiceStatus.RUNNING;
    }

    @Override
    public void stop() {
        status = ServiceStatus.STOPPED;
    }

    @Override
    public ServiceStatus getStatus() {
        return status;
    }
}
