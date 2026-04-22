package com.moviesearch.service.step.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MockIngestIntoQdrantServiceTest {

    @Test
    void execute_emitsAllLogLines() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockIngestIntoQdrantService().execute(logs::add, false);

        assertThat(logs).hasSize(3);
        assertThat(logs.get(0)).isEqualTo("[MOCK] Connecting to Qdrant...");
        assertThat(logs.get(1)).isEqualTo("[MOCK] Ingesting batch 1/100...");
        assertThat(logs.get(2)).isEqualTo("[MOCK] Done. 42,306 points ingested.");
    }

    @Test
    void execute_allLinesHaveMockPrefix() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockIngestIntoQdrantService().execute(logs::add, false);

        assertThat(logs).allMatch(line -> line.startsWith("[MOCK]"));
    }
}
