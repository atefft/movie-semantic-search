package com.moviesearch.service.step.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MockGenerateEmbeddingsServiceTest {

    @Test
    void execute_emitsAllLogLines() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockGenerateEmbeddingsService().execute(logs::add, false);

        assertThat(logs).hasSize(4);
        assertThat(logs.get(0)).isEqualTo("[MOCK] Warming up Triton...");
        assertThat(logs.get(1)).isEqualTo("[MOCK] Embedding batch 1/663...");
        assertThat(logs.get(2)).isEqualTo("[MOCK] Embedding batch 663/663...");
        assertThat(logs.get(3)).isEqualTo("[MOCK] Done. embeddings.npy written.");
    }

    @Test
    void execute_allLinesHaveMockPrefix() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockGenerateEmbeddingsService().execute(logs::add, false);

        assertThat(logs).allMatch(line -> line.startsWith("[MOCK]"));
    }
}
