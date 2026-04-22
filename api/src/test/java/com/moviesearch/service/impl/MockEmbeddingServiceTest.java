package com.moviesearch.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmbeddingServiceTest {

    private MockEmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new MockEmbeddingService();
    }

    private ListAppender<ILoggingEvent> attachLogAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // --- Happy paths ---

    @Test
    void embed_returnsCorrectDimensions() {
        EmbeddingResponse response = service.embed(new EmbeddingRequest("hello world"));

        assertThat(response.getVector()).hasSize(384);
    }

    @Test
    void embed_alternatingPattern() {
        float[] vector = service.embed(new EmbeddingRequest("hello world")).getVector();

        for (int i = 0; i < vector.length; i++) {
            if (i % 2 == 0) {
                assertThat(vector[i]).isEqualTo(0.1f);
            } else {
                assertThat(vector[i]).isEqualTo(-0.1f);
            }
        }
    }

    @Test
    void embed_pattern_spotCheck() {
        float[] vector = service.embed(new EmbeddingRequest("hello world")).getVector();

        assertThat(vector[0]).isEqualTo(0.1f);
        assertThat(vector[1]).isEqualTo(-0.1f);
        assertThat(vector[382]).isEqualTo(0.1f);
        assertThat(vector[383]).isEqualTo(-0.1f);
    }

    @Test
    void embed_logMessages() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(MockEmbeddingService.class);
        Logger logger = (Logger) LoggerFactory.getLogger(MockEmbeddingService.class);

        try {
            service.embed(new EmbeddingRequest("hello world"));

            List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

            assertThat(messages).contains("EmbeddingService.embed called [text='hello world']");
            assertThat(messages).contains("EmbeddingService.embed returning vector [dimensions=384]");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void embed_unicodeText() {
        EmbeddingResponse response = service.embed(new EmbeddingRequest("宇宙探索"));

        assertThat(response.getVector()).hasSize(384);
    }
}
