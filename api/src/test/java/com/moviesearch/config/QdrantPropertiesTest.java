package com.moviesearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class QdrantPropertiesTest {

    @Autowired
    private QdrantProperties qdrantProperties;

    @Test
    void defaultBaseUrlIsLocalhost6333() {
        assertThat(qdrantProperties.getBaseUrl()).isEqualTo("http://localhost:6333");
    }

    @Test
    void defaultMockIsFalse() {
        assertThat(qdrantProperties.isMock()).isFalse();
    }
}
