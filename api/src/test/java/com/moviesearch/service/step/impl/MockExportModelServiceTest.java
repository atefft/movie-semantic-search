package com.moviesearch.service.step.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MockExportModelServiceTest {

    @Test
    void execute_emitsAllLogLines() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockExportModelService().execute(logs::add, false);

        assertThat(logs).hasSize(3);
        assertThat(logs.get(0)).isEqualTo("[MOCK] Exporting all-MiniLM-L6-v2 to ONNX...");
        assertThat(logs.get(1)).isEqualTo("[MOCK] Quantizing model...");
        assertThat(logs.get(2)).isEqualTo("[MOCK] Done. model.onnx written.");
    }

    @Test
    void execute_allLinesHaveMockPrefix() {
        var logs = new CopyOnWriteArrayList<String>();
        new MockExportModelService().execute(logs::add, false);

        assertThat(logs).allMatch(line -> line.startsWith("[MOCK]"));
    }
}
