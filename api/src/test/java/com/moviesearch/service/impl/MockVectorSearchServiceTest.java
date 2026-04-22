package com.moviesearch.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockVectorSearchServiceTest {

    private MockVectorSearchService service;
    private static final float[] ANY_VECTOR = new float[384];

    @BeforeEach
    void setUp() {
        service = new MockVectorSearchService();
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
    void search_limitOne() {
        VectorSearchResponse response = service.search(new VectorSearchRequest(ANY_VECTOR, 1));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Cast Away");
    }

    @Test
    void search_limitThree() {
        VectorSearchResponse response = service.search(new VectorSearchRequest(ANY_VECTOR, 3));

        assertThat(response.getResults()).hasSize(3);
        assertThat(response.getResults().get(0).getTitle()).isEqualTo("Cast Away");
        assertThat(response.getResults().get(1).getTitle()).isEqualTo("The Martian");
        assertThat(response.getResults().get(2).getTitle()).isEqualTo("Gravity");
    }

    @Test
    void search_limitFive() {
        VectorSearchResponse response = service.search(new VectorSearchRequest(ANY_VECTOR, 5));

        assertThat(response.getResults()).hasSize(5);
    }

    @Test
    void search_limitExceedsAvailable() {
        VectorSearchResponse response = service.search(new VectorSearchRequest(ANY_VECTOR, 10));

        assertThat(response.getResults()).hasSize(5);
    }

    // --- Edge cases ---

    @Test
    void search_limitZero() {
        VectorSearchResponse response = service.search(new VectorSearchRequest(ANY_VECTOR, 0));

        assertThat(response.getResults()).isEmpty();
    }

    // --- Log assertions ---

    @Test
    void search_logMessages() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(MockVectorSearchService.class);
        Logger logger = (Logger) LoggerFactory.getLogger(MockVectorSearchService.class);

        try {
            service.search(new VectorSearchRequest(ANY_VECTOR, 3));

            List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

            assertThat(messages).contains("VectorSearchService.search called [limit=3]");
            assertThat(messages).contains("VectorSearchService.search returning 3 results [requested=3]");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
