package com.moviesearch.service.impl;

import com.moviesearch.model.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class MockTritonServiceManagerTest {

    private MockTritonServiceManager manager;

    @BeforeEach
    void setUp() {
        manager = new MockTritonServiceManager();
    }

    @Test
    void getStatus_initialState() {
        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    void start_setsStatusToRunning() {
        manager.start();

        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.RUNNING);
    }

    @Test
    void stop_setsStatusToStopped() {
        manager.start();
        manager.stop();

        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }

    @Test
    void start_whenAlreadyRunning() {
        manager.start();

        assertThatNoException().isThrownBy(() -> manager.start());
        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.RUNNING);
    }

    @Test
    void stop_whenAlreadyStopped() {
        assertThatNoException().isThrownBy(() -> manager.stop());
        assertThat(manager.getStatus()).isEqualTo(ServiceStatus.STOPPED);
    }
}
