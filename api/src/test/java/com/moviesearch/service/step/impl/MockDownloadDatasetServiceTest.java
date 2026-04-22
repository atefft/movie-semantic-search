package com.moviesearch.service.step.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MockDownloadDatasetServiceTest {

    @Test
    void execute_emitsAllLogLines() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockDownloadDatasetService().execute(logs::add, false);

        assertThat(logs).hasSize(3);
        assertThat(logs.get(0)).isEqualTo("[MOCK] Downloading CMU corpus...");
        assertThat(logs.get(1)).isEqualTo("[MOCK] Extracting archive...");
        assertThat(logs.get(2)).isEqualTo("[MOCK] Done. 42,306 summaries written.");
    }

    @Test
    void execute_allLinesHaveMockPrefix() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockDownloadDatasetService().execute(logs::add, false);

        assertThat(logs).allMatch(line -> line.startsWith("[MOCK]"));
    }
}
