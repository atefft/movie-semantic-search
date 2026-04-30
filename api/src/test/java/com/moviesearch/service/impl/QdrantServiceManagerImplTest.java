package com.moviesearch.service.impl;

import com.moviesearch.config.QdrantProperties;
import com.moviesearch.exception.QdrantServiceException;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.DockerComposeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QdrantServiceManagerImplTest {

    @Mock
    private DockerComposeRunner dockerComposeRunner;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<Void> httpResponse;

    private QdrantProperties qdrantProperties;
    private QdrantServiceManagerImpl manager;

    @BeforeEach
    void setUp() {
        qdrantProperties = new QdrantProperties();
        qdrantProperties.setBaseUrl("http://localhost:6333");
        manager = new QdrantServiceManagerImpl(dockerComposeRunner, httpClient, qdrantProperties);
    }

    @Test
    void start_callsDockerComposeUpDQdrant() throws IOException, InterruptedException {
        manager.start();

        verify(dockerComposeRunner).run("up", "-d", "qdrant");
    }

    @Test
    void stop_callsDockerComposeStopQdrant() throws IOException, InterruptedException {
        manager.stop();

        verify(dockerComposeRunner).run("stop", "qdrant");
    }

    @Test
    void start_ioException_throwsQdrantServiceExceptionWithStartFailed() throws IOException, InterruptedException {
        IOException cause = new IOException("process failed");
        doThrow(cause).when(dockerComposeRunner).run("up", "-d", "qdrant");

        assertThatThrownBy(() -> manager.start())
                .isInstanceOf(QdrantServiceException.class)
                .hasMessage(QdrantServiceException.START_FAILED)
                .hasCause(cause);
    }

    @Test
    void start_interruptedException_throwsQdrantServiceExceptionAndSetsInterruptFlag() throws IOException, InterruptedException {
        InterruptedException cause = new InterruptedException("interrupted");
        doThrow(cause).when(dockerComposeRunner).run("up", "-d", "qdrant");

        assertThatThrownBy(() -> manager.start())
                .isInstanceOf(QdrantServiceException.class)
                .hasMessage(QdrantServiceException.START_FAILED)
                .hasCause(cause);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // clear for other tests
    }

    @Test
    void stop_ioException_throwsQdrantServiceExceptionWithStopFailed() throws IOException, InterruptedException {
        IOException cause = new IOException("process failed");
        doThrow(cause).when(dockerComposeRunner).run("stop", "qdrant");

        assertThatThrownBy(() -> manager.stop())
                .isInstanceOf(QdrantServiceException.class)
                .hasMessage(QdrantServiceException.STOP_FAILED)
                .hasCause(cause);
    }

    @Test
    void stop_interruptedException_throwsQdrantServiceExceptionAndSetsInterruptFlag() throws IOException, InterruptedException {
        InterruptedException cause = new InterruptedException("interrupted");
        doThrow(cause).when(dockerComposeRunner).run("stop", "qdrant");

        assertThatThrownBy(() -> manager.stop())
                .isInstanceOf(QdrantServiceException.class)
                .hasMessage(QdrantServiceException.STOP_FAILED)
                .hasCause(cause);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // clear for other tests
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStatus_http200_returnsRunning() throws IOException, InterruptedException {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.RUNNING);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStatus_http503_returnsStopped() throws IOException, InterruptedException {
        when(httpResponse.statusCode()).thenReturn(503);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStatus_http404_returnsStopped() throws IOException, InterruptedException {
        when(httpResponse.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStatus_ioException_returnsStopped() throws IOException, InterruptedException {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection refused"));

        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStatus_interruptedException_returnsStopped() throws IOException, InterruptedException {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("interrupted"));

        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStatus_usesHealthEndpoint() throws IOException, InterruptedException {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        manager.getStatus();

        verify(httpClient).send(
                org.mockito.ArgumentMatchers.argThat(req -> req.uri().toString().equals("http://localhost:6333/health")),
                any(HttpResponse.BodyHandler.class));
    }
}
