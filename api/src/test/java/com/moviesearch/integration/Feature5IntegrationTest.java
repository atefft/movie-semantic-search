package com.moviesearch.integration;

import com.moviesearch.service.DockerComposeRunner;
import com.moviesearch.service.step.impl.GenerateEmbeddingsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Feature5IntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("project.root", tempDir::toString);
        registry.add("tmdb.api-key", () -> "test-key");
    }

    @MockBean
    private DockerComposeRunner dockerComposeRunner;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private GenerateEmbeddingsServiceImpl generateEmbeddingsService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(dockerComposeRunner);
    }

    @Test
    void contextLoads() {
        // application context starting without error verifies this criterion
    }

    @Test
    void health_returnsJsonWithTritonAndQdrantFields() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/operator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("triton");
        assertThat(response.getBody()).containsKey("qdrant");
    }

    @Test
    void startTriton_delegatesToTritonServiceManagerImpl() throws IOException, InterruptedException {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/operator/service/triton/start", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dockerComposeRunner).run("up", "-d", "triton");
    }

    @Test
    void stopTriton_delegatesToTritonServiceManagerImpl() throws IOException, InterruptedException {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/operator/service/triton/stop", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dockerComposeRunner).run("stop", "triton");
    }

    @Test
    void startQdrant_delegatesToQdrantServiceManagerImpl() throws IOException, InterruptedException {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/operator/service/qdrant/start", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dockerComposeRunner).run("up", "-d", "qdrant");
    }

    @Test
    void stopQdrant_delegatesToQdrantServiceManagerImpl() throws IOException, InterruptedException {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/operator/service/qdrant/stop", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dockerComposeRunner).run("stop", "qdrant");
    }

    @Test
    void generateEmbeddings_skipsWhenIdempotencyFilesExist() throws IOException {
        Path embeddingsDir = tempDir.resolve(Path.of("data", "embeddings"));
        Files.createDirectories(embeddingsDir);
        Files.writeString(embeddingsDir.resolve("embeddings.npy"), "dummy");
        Files.writeString(embeddingsDir.resolve("metadata.json"), "{}");

        List<String> logs = new ArrayList<>();
        generateEmbeddingsService.execute(logs::add, false);

        assertThat(logs).anyMatch(line -> line.contains("skipping"));
    }
}
