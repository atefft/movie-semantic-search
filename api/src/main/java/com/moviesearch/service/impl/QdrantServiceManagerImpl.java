package com.moviesearch.service.impl;

import com.moviesearch.config.QdrantProperties;
import com.moviesearch.exception.QdrantServiceException;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.DockerComposeRunner;
import com.moviesearch.service.QdrantServiceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class QdrantServiceManagerImpl implements QdrantServiceManager {

    private final DockerComposeRunner dockerComposeRunner;
    private final HttpClient httpClient;
    private final QdrantProperties qdrantProperties;

    @Autowired
    public QdrantServiceManagerImpl(DockerComposeRunner dockerComposeRunner, QdrantProperties qdrantProperties) {
        this(dockerComposeRunner, HttpClient.newHttpClient(), qdrantProperties);
    }

    QdrantServiceManagerImpl(DockerComposeRunner dockerComposeRunner, HttpClient httpClient, QdrantProperties qdrantProperties) {
        this.dockerComposeRunner = dockerComposeRunner;
        this.httpClient = httpClient;
        this.qdrantProperties = qdrantProperties;
    }

    @Override
    public void start() {
        try {
            dockerComposeRunner.run("up", "-d", "qdrant");
        } catch (IOException e) {
            throw new QdrantServiceException(QdrantServiceException.START_FAILED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdrantServiceException(QdrantServiceException.START_FAILED, e);
        }
    }

    @Override
    public void stop() {
        try {
            dockerComposeRunner.run("stop", "qdrant");
        } catch (IOException e) {
            throw new QdrantServiceException(QdrantServiceException.STOP_FAILED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdrantServiceException(QdrantServiceException.STOP_FAILED, e);
        }
    }

    @Override
    public ServiceStatus getStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(qdrantProperties.getBaseUrl() + "/health"))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? ServiceStatus.RUNNING : ServiceStatus.STOPPED;
        } catch (Exception e) {
            return ServiceStatus.STOPPED;
        }
    }
}
