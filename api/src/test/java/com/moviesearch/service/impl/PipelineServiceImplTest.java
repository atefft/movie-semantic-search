package com.moviesearch.service.impl;

import com.moviesearch.exception.DownloadDatasetServiceException;
import com.moviesearch.model.PipelineStep;
import com.moviesearch.model.RunAllRequest;
import com.moviesearch.model.RunStepRequest;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.model.StepStatus;
import com.moviesearch.service.QdrantServiceManager;
import com.moviesearch.service.TritonServiceManager;
import com.moviesearch.service.step.DownloadDatasetService;
import com.moviesearch.service.step.EnrichWithTmdbService;
import com.moviesearch.service.step.ExportModelService;
import com.moviesearch.service.step.GenerateEmbeddingsService;
import com.moviesearch.service.step.IngestIntoQdrantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineServiceImplTest {

    @Mock private DownloadDatasetService downloadDatasetService;
    @Mock private ExportModelService exportModelService;
    @Mock private GenerateEmbeddingsService generateEmbeddingsService;
    @Mock private IngestIntoQdrantService ingestIntoQdrantService;
    @Mock private EnrichWithTmdbService enrichWithTmdbService;
    @Mock private TritonServiceManager tritonManager;
    @Mock private QdrantServiceManager qdrantManager;

    @InjectMocks
    private PipelineServiceImpl service;

    private CompletableFuture<Integer> future() {
        return new CompletableFuture<>();
    }

    @Test
    void getStatus_initialState() {
        List<StepStatus> statuses = service.getStatus().getSteps();

        assertThat(statuses).hasSize(5);
        for (StepStatus s : statuses) {
            assertThat(s.isDone()).isFalse();
            assertThat(s.isRunning()).isFalse();
        }
        assertThat(statuses.get(0).isPrereqsMet()).isTrue();  // step 1
        assertThat(statuses.get(1).isPrereqsMet()).isTrue();  // step 2
        assertThat(statuses.get(2).isPrereqsMet()).isFalse(); // step 3
        assertThat(statuses.get(3).isPrereqsMet()).isFalse(); // step 4
        assertThat(statuses.get(4).isPrereqsMet()).isFalse(); // step 5
    }

    @Test
    void runStep_step1_happyPath() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> done = future();

        boolean started = service.runStep(new RunStepRequest(PipelineStep.DOWNLOAD_DATASET), logs::add, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isZero();
        verify(downloadDatasetService).execute(any(), eq(false));
        assertThat(service.done.get(PipelineStep.DOWNLOAD_DATASET)).isTrue();
        assertThat(service.running.get()).isNull();
    }

    @Test
    void runStep_step2_happyPath() throws Exception {
        CompletableFuture<Integer> done = future();

        boolean started = service.runStep(new RunStepRequest(PipelineStep.EXPORT_MODEL), line -> {}, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isZero();
        verify(exportModelService).execute(any(), eq(false));
        assertThat(service.done.get(PipelineStep.EXPORT_MODEL)).isTrue();
    }

    @Test
    void runStep_prereqsNotMet_step3() {
        boolean started = service.runStep(new RunStepRequest(PipelineStep.GENERATE_EMBEDDINGS), line -> {}, code -> {});

        assertThat(started).isFalse();
        verifyNoInteractions(generateEmbeddingsService);
    }

    @Test
    void runStep_prereqsMet_step3() throws Exception {
        service.done.put(PipelineStep.DOWNLOAD_DATASET, true);
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        CompletableFuture<Integer> done = future();

        boolean started = service.runStep(new RunStepRequest(PipelineStep.GENERATE_EMBEDDINGS), line -> {}, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isZero();
        verify(generateEmbeddingsService).execute(any(), eq(false));
        assertThat(service.done.get(PipelineStep.GENERATE_EMBEDDINGS)).isTrue();
    }

    @Test
    void runStep_prereqsNotMet_step4() {
        boolean started = service.runStep(new RunStepRequest(PipelineStep.INGEST_INTO_QDRANT), line -> {}, code -> {});
        assertThat(started).isFalse();
    }

    @Test
    void runStep_prereqsNotMet_step5() {
        boolean started = service.runStep(new RunStepRequest(PipelineStep.ENRICH_WITH_TMDB), line -> {}, code -> {});
        assertThat(started).isFalse();
    }

    @Test
    void runStep_alreadyRunning() {
        service.running.set(PipelineStep.DOWNLOAD_DATASET);

        boolean started = service.runStep(new RunStepRequest(PipelineStep.EXPORT_MODEL), line -> {}, code -> {});

        assertThat(started).isFalse();
    }

    @Test
    void runStep_stepThrows() throws Exception {
        doThrow(new DownloadDatasetServiceException("Download failed"))
            .when(downloadDatasetService).execute(any(), eq(false));
        List<String> logs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> done = future();

        boolean started = service.runStep(new RunStepRequest(PipelineStep.DOWNLOAD_DATASET), logs::add, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(logs).anyMatch(l -> l.startsWith("[ERROR]"));
        assertThat(Boolean.TRUE.equals(service.done.get(PipelineStep.DOWNLOAD_DATASET))).isFalse();
        assertThat(service.running.get()).isNull();
    }

    @Test
    void runStep_tritonNotRunning_step3() throws Exception {
        service.done.put(PipelineStep.DOWNLOAD_DATASET, true);
        // tritonManager.getStatus() returns null by default (treated as not RUNNING)
        List<String> logs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> done = future();

        boolean started = service.runStep(new RunStepRequest(PipelineStep.GENERATE_EMBEDDINGS), logs::add, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(logs).anyMatch(l -> l.startsWith("[ERROR]"));
        verify(generateEmbeddingsService, never()).execute(any(), eq(false));
        assertThat(Boolean.TRUE.equals(service.done.get(PipelineStep.GENERATE_EMBEDDINGS))).isFalse();
        assertThat(service.running.get()).isNull();
    }

    @Test
    void runStep_qdrantNotRunning_step4() throws Exception {
        service.done.put(PipelineStep.DOWNLOAD_DATASET, true);
        service.done.put(PipelineStep.GENERATE_EMBEDDINGS, true);
        // qdrantManager.getStatus() returns null by default (treated as not RUNNING)
        CompletableFuture<Integer> done = future();

        boolean started = service.runStep(new RunStepRequest(PipelineStep.INGEST_INTO_QDRANT), line -> {}, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        verify(ingestIntoQdrantService, never()).execute(any(), eq(false));
    }

    @Test
    void runStep_qdrantNotRunning_step5() throws Exception {
        service.done.put(PipelineStep.DOWNLOAD_DATASET, true);
        service.done.put(PipelineStep.GENERATE_EMBEDDINGS, true);
        service.done.put(PipelineStep.INGEST_INTO_QDRANT, true);
        // qdrantManager.getStatus() returns null by default (treated as not RUNNING)
        CompletableFuture<Integer> done = future();

        boolean started = service.runStep(new RunStepRequest(PipelineStep.ENRICH_WITH_TMDB), line -> {}, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        verify(enrichWithTmdbService, never()).execute(any(), eq(false));
    }

    @Test
    void runAll_runsAllStepsInOrder() throws Exception {
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        when(qdrantManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        List<String> logs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> done = future();

        boolean started = service.runAll(new RunAllRequest(false), logs::add, done::complete);

        assertThat(started).isTrue();
        assertThat(done.get(5, TimeUnit.SECONDS)).isZero();
        verify(downloadDatasetService).execute(any(), eq(false));
        verify(exportModelService).execute(any(), eq(false));
        verify(generateEmbeddingsService).execute(any(), eq(false));
        verify(ingestIntoQdrantService).execute(any(), eq(false));
        verify(enrichWithTmdbService).execute(any(), eq(false));
        assertThat(service.done.get(PipelineStep.DOWNLOAD_DATASET)).isTrue();
        assertThat(service.done.get(PipelineStep.EXPORT_MODEL)).isTrue();
        assertThat(service.done.get(PipelineStep.GENERATE_EMBEDDINGS)).isTrue();
        assertThat(service.done.get(PipelineStep.INGEST_INTO_QDRANT)).isTrue();
        assertThat(service.done.get(PipelineStep.ENRICH_WITH_TMDB)).isTrue();
        assertThat(logs).anyMatch(l -> l.contains("Step 1"));
        assertThat(logs).anyMatch(l -> l.contains("Step 2"));
        assertThat(logs).anyMatch(l -> l.contains("Step 3"));
        assertThat(logs).anyMatch(l -> l.contains("Step 4"));
        assertThat(logs).anyMatch(l -> l.contains("Step 5"));
    }

    @Test
    void runAll_skipsDoneSteps() throws Exception {
        service.done.put(PipelineStep.DOWNLOAD_DATASET, true);
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        when(qdrantManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        CompletableFuture<Integer> done = future();

        service.runAll(new RunAllRequest(false), line -> {}, done::complete);
        done.get(5, TimeUnit.SECONDS);

        verify(downloadDatasetService, never()).execute(any(), eq(false));
        verify(exportModelService).execute(any(), eq(false));
        verify(generateEmbeddingsService).execute(any(), eq(false));
        verify(ingestIntoQdrantService).execute(any(), eq(false));
        verify(enrichWithTmdbService).execute(any(), eq(false));
    }

    @Test
    void runAll_forceResetsState() throws Exception {
        service.done.put(PipelineStep.DOWNLOAD_DATASET, true);
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        when(qdrantManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        CompletableFuture<Integer> done = future();

        service.runAll(new RunAllRequest(true), line -> {}, done::complete);
        done.get(5, TimeUnit.SECONDS);

        verify(downloadDatasetService).execute(any(), eq(true));
        verify(exportModelService).execute(any(), eq(true));
        verify(generateEmbeddingsService).execute(any(), eq(true));
        verify(ingestIntoQdrantService).execute(any(), eq(true));
        verify(enrichWithTmdbService).execute(any(), eq(true));
    }

    @Test
    void runAll_alreadyRunning() {
        service.running.set(PipelineStep.DOWNLOAD_DATASET);

        boolean started = service.runAll(new RunAllRequest(false), line -> {}, code -> {});

        assertThat(started).isFalse();
    }

    @Test
    void runAll_abortsOnFirstFailure() throws Exception {
        doThrow(new DownloadDatasetServiceException("Download failed"))
            .when(downloadDatasetService).execute(any(), eq(false));
        List<String> logs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> done = future();

        service.runAll(new RunAllRequest(false), logs::add, done::complete);

        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(logs).anyMatch(l -> l.startsWith("[ERROR]"));
        verify(exportModelService, never()).execute(any(), eq(false));
    }

    @Test
    void runAll_abortsWhenTritonNotRunning() throws Exception {
        // Triton STOPPED (default mock null), Qdrant irrelevant
        List<String> logs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> done = future();

        service.runAll(new RunAllRequest(false), logs::add, done::complete);

        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(logs).anyMatch(l -> l.startsWith("[ERROR]"));
        verify(downloadDatasetService).execute(any(), eq(false));
        verify(exportModelService).execute(any(), eq(false));
        verify(generateEmbeddingsService, never()).execute(any(), eq(false));
        verify(ingestIntoQdrantService, never()).execute(any(), eq(false));
        verify(enrichWithTmdbService, never()).execute(any(), eq(false));
    }

    @Test
    void runAll_abortsWhenQdrantNotRunning() throws Exception {
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        // qdrantManager.getStatus() returns null by default (treated as not RUNNING)
        List<String> logs = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> done = future();

        service.runAll(new RunAllRequest(false), logs::add, done::complete);

        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(logs).anyMatch(l -> l.startsWith("[ERROR]"));
        verify(downloadDatasetService).execute(any(), eq(false));
        verify(exportModelService).execute(any(), eq(false));
        verify(generateEmbeddingsService).execute(any(), eq(false));
        verify(ingestIntoQdrantService, never()).execute(any(), eq(false));
        verify(enrichWithTmdbService, never()).execute(any(), eq(false));
    }

    @Test
    void fromNumber_invalidStep() {
        assertThatThrownBy(() -> PipelineStep.fromNumber(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PipelineStep.fromNumber(6))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
