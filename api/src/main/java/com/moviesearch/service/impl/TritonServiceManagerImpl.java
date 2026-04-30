package com.moviesearch.service.impl;

import com.moviesearch.config.TritonProperties;
import com.moviesearch.exception.TritonServiceException;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.DockerComposeRunner;
import com.moviesearch.service.TritonServiceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class TritonServiceManagerImpl implements TritonServiceManager {

    private static final int HEALTH_PORT = 8000;

    private final DockerComposeRunner dockerComposeRunner;
    private final HttpClient httpClient;
    private final TritonProperties tritonProperties;

    @Autowired
    public TritonServiceManagerImpl(DockerComposeRunner dockerComposeRunner, TritonProperties tritonProperties) {
        this(dockerComposeRunner, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), tritonProperties);
    }

    TritonServiceManagerImpl(DockerComposeRunner dockerComposeRunner, HttpClient httpClient, TritonProperties tritonProperties) {
        this.dockerComposeRunner = dockerComposeRunner;
        this.httpClient = httpClient;
        this.tritonProperties = tritonProperties;
    }

    @Override
    public void start() {
        try {
            dockerComposeRunner.run("up", "-d", "triton");
        } catch (IOException e) {
            throw new TritonServiceException(TritonServiceException.START_FAILED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TritonServiceException(TritonServiceException.START_FAILED, e);
        }
    }

    @Override
    public void stop() {
        try {
            dockerComposeRunner.run("stop", "triton");
        } catch (IOException e) {
            throw new TritonServiceException(TritonServiceException.STOP_FAILED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TritonServiceException(TritonServiceException.STOP_FAILED, e);
        }
    }

    @Override
    public ServiceStatus getStatus() {
        try {
            String url = "http://" + tritonProperties.getHost() + ":" + HEALTH_PORT + "/v2/health/ready";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? ServiceStatus.RUNNING : ServiceStatus.STOPPED;
        } catch (Exception e) {
            return ServiceStatus.STOPPED;
        }
    }
}
