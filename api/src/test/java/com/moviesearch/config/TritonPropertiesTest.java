package com.moviesearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TritonPropertiesTest {

    @Autowired
    private TritonProperties tritonProperties;

    @Test
    void defaultModelNameIsAllMiniLML6v2() {
        assertThat(tritonProperties.getModelName()).isEqualTo("all-MiniLM-L6-v2");
    }

    @Test
    void defaultDeadlineMsIs5000() {
        assertThat(tritonProperties.getDeadlineMs()).isEqualTo(5000L);
    }
}
