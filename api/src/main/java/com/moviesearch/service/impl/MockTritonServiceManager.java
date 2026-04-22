package com.moviesearch.service.impl;

import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.TritonServiceManager;
import org.springframework.stereotype.Service;

@Service
public class MockTritonServiceManager implements TritonServiceManager {

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
