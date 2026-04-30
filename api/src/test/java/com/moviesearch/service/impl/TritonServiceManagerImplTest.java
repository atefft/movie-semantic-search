package com.moviesearch.service.impl;

import com.moviesearch.config.TritonProperties;
import com.moviesearch.exception.TritonServiceException;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.DockerComposeRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TritonServiceManagerImplTest {

    @Mock
    private DockerComposeRunner dockerComposeRunner;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<Void> response;

    private TritonServiceManagerImpl service;

    @BeforeEach
    void setUp() {
        TritonProperties props = new TritonProperties();
        props.setHost("localhost");
        service = new TritonServiceManagerImpl(dockerComposeRunner, httpClient, props);
    }

    @Test
    void start_callsDockerComposeRunnerWithCorrectArgs() throws Exception {
        service.start();
        verify(dockerComposeRunner).run("up", "-d", "triton");
    }

    @Test
    void stop_callsDockerComposeRunnerWithCorrectArgs() throws Exception {
        service.stop();
        verify(dockerComposeRunner).run("stop", "triton");
    }

    @Test
    void start_ioException_throwsTritonServiceExceptionWithStartFailed() throws Exception {
        IOException cause = new IOException("docker error");
        doThrow(cause).when(dockerComposeRunner).run("up", "-d", "triton");

        assertThatThrownBy(() -> service.start())
                .isInstanceOf(TritonServiceException.class)
                .hasMessage(TritonServiceException.START_FAILED)
                .hasCause(cause);
    }

    @Test
    void start_interruptedException_throwsTritonServiceExceptionAndSetsInterruptFlag() throws Exception {
        InterruptedException cause = new InterruptedException("interrupted");
        doThrow(cause).when(dockerComposeRunner).run("up", "-d", "triton");

        Thread.interrupted(); // clear any existing interrupt flag
        assertThatThrownBy(() -> service.start())
                .isInstanceOf(TritonServiceException.class)
                .hasMessage(TritonServiceException.START_FAILED)
                .hasCause(cause);

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void stop_ioException_throwsTritonServiceExceptionWithStopFailed() throws Exception {
        IOException cause = new IOException("docker error");
        doThrow(cause).when(dockerComposeRunner).run("stop", "triton");

        assertThatThrownBy(() -> service.stop())
                .isInstanceOf(TritonServiceException.class)
                .hasMessage(TritonServiceException.STOP_FAILED)
                .hasCause(cause);
    }

    @Test
    void stop_interruptedException_throwsTritonServiceExceptionAndSetsInterruptFlag() throws Exception {
        InterruptedException cause = new InterruptedException("interrupted");
        doThrow(cause).when(dockerComposeRunner).run("stop", "triton");

        Thread.interrupted(); // clear any existing interrupt flag
        assertThatThrownBy(() -> service.stop())
                .isInstanceOf(TritonServiceException.class)
                .hasMessage(TritonServiceException.STOP_FAILED)
                .hasCause(cause);

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void getStatus_http200_returnsRunning() throws Exception {
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(200);

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.RUNNING);
    }

    @Test
    void getStatus_http503_returnsStopped() throws Exception {
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(503);

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    void getStatus_http404_returnsStopped() throws Exception {
        doReturn(response).when(httpClient).send(any(), any());
        when(response.statusCode()).thenReturn(404);

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    void getStatus_ioException_returnsStopped() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    void getStatus_interruptedException_returnsStopped() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new InterruptedException("interrupted"));

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }
}
