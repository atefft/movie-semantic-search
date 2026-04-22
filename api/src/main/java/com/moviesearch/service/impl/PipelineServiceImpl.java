package com.moviesearch.service.impl;

import com.moviesearch.exception.QdrantServiceException;
import com.moviesearch.exception.TritonServiceException;
import com.moviesearch.model.PipelineStep;
import com.moviesearch.model.PipelineStatusResponse;
import com.moviesearch.model.RunAllRequest;
import com.moviesearch.model.RunStepRequest;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.model.StepStatus;
import com.moviesearch.service.PipelineService;
import com.moviesearch.service.QdrantServiceManager;
import com.moviesearch.service.TritonServiceManager;
import com.moviesearch.service.step.DownloadDatasetService;
import com.moviesearch.service.step.EnrichWithTmdbService;
import com.moviesearch.service.step.ExportModelService;
import com.moviesearch.service.step.GenerateEmbeddingsService;
import com.moviesearch.service.step.IngestIntoQdrantService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.moviesearch.model.PipelineStep.*;

@Service
public class PipelineServiceImpl implements PipelineService {

    @FunctionalInterface
    private interface PipelineStepExecutor {
        void execute(Consumer<String> onLog, boolean force);
    }

    EnumMap<PipelineStep, Boolean> done = new EnumMap<>(PipelineStep.class);
    final AtomicReference<PipelineStep> running = new AtomicReference<>(null);
    private final EnumMap<PipelineStep, PipelineStepExecutor> stepExecutors;
    private final TritonServiceManager tritonManager;
    private final QdrantServiceManager qdrantManager;

    public PipelineServiceImpl(
            DownloadDatasetService d, ExportModelService e,
            GenerateEmbeddingsService g, IngestIntoQdrantService i, EnrichWithTmdbService t,
            TritonServiceManager tritonManager, QdrantServiceManager qdrantManager) {
        stepExecutors = new EnumMap<>(PipelineStep.class);
        stepExecutors.put(DOWNLOAD_DATASET,    d::execute);
        stepExecutors.put(EXPORT_MODEL,        e::execute);
        stepExecutors.put(GENERATE_EMBEDDINGS, g::execute);
        stepExecutors.put(INGEST_INTO_QDRANT,  i::execute);
        stepExecutors.put(ENRICH_WITH_TMDB,    t::execute);
        this.tritonManager = tritonManager;
        this.qdrantManager = qdrantManager;
    }

    private boolean isDone(PipelineStep step) {
        return Boolean.TRUE.equals(done.get(step));
    }

    private boolean prereqsMet(PipelineStep step) {
        return switch (step) {
            case DOWNLOAD_DATASET, EXPORT_MODEL -> true;
            case GENERATE_EMBEDDINGS -> isDone(DOWNLOAD_DATASET);
            case INGEST_INTO_QDRANT -> isDone(GENERATE_EMBEDDINGS);
            case ENRICH_WITH_TMDB -> isDone(INGEST_INTO_QDRANT);
        };
    }

    private void checkServicePrereqs(PipelineStep step) {
        switch (step) {
            case GENERATE_EMBEDDINGS -> {
                if (tritonManager.getStatus() != ServiceStatus.RUNNING)
                    throw new TritonServiceException(TritonServiceException.CONNECTION_REFUSED);
            }
            case INGEST_INTO_QDRANT, ENRICH_WITH_TMDB -> {
                if (qdrantManager.getStatus() != ServiceStatus.RUNNING)
                    throw new QdrantServiceException(QdrantServiceException.CONNECTION_REFUSED);
            }
        }
    }

    @Override
    public PipelineStatusResponse getStatus() {
        PipelineStep currentlyRunning = running.get();
        List<StepStatus> statuses = new ArrayList<>(5);
        for (PipelineStep step : PipelineStep.values()) {
            statuses.add(new StepStatus(step, isDone(step), prereqsMet(step), step == currentlyRunning));
        }
        return new PipelineStatusResponse(statuses);
    }

    @Override
    public boolean runStep(RunStepRequest request, Consumer<String> onLog, Consumer<Integer> onDone) {
        PipelineStep step = request.getStep();
        if (running.get() != null) return false;
        if (!prereqsMet(step)) return false;
        running.set(step);
        Thread.ofVirtual().start(() -> {
            try {
                checkServicePrereqs(step);
                stepExecutors.get(step).execute(onLog, false);
                done.put(step, true);
                running.set(null);
                onDone.accept(0);
            } catch (Exception e) {
                onLog.accept("[ERROR] " + e.getMessage());
                running.set(null);
                onDone.accept(1);
            }
        });
        return true;
    }

    @Override
    public boolean runAll(RunAllRequest request, Consumer<String> onLog, Consumer<Integer> onDone) {
        if (running.get() != null) return false;
        if (request.isForce()) done.clear();
        Thread.ofVirtual().start(() -> {
            for (PipelineStep step : PipelineStep.values()) {
                if (isDone(step)) continue;
                running.set(step);
                onLog.accept("=== Step " + step.getNumber() + ": " + step.getDisplayName() + " ===");
                try {
                    checkServicePrereqs(step);
                    stepExecutors.get(step).execute(onLog, request.isForce());
                } catch (Exception e) {
                    onLog.accept("[ERROR] " + e.getMessage());
                    running.set(null);
                    onDone.accept(1);
                    return;
                }
                done.put(step, true);
                running.set(null);
            }
            onDone.accept(0);
        });
        return true;
    }
}
