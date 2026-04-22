package com.moviesearch.service;

import com.moviesearch.model.PipelineStatusResponse;
import com.moviesearch.model.RunAllRequest;
import com.moviesearch.model.RunStepRequest;
import java.util.function.Consumer;

public interface PipelineService {

    /** Returns the current status of all 5 pipeline steps. */
    PipelineStatusResponse getStatus();

    /**
     * Starts a single step asynchronously.
     * Calls onLog for each log line, then onDone(exitCode) when complete.
     * @return true if started; false if already running or prereqs not met
     */
    boolean runStep(RunStepRequest request, Consumer<String> onLog, Consumer<Integer> onDone);

    /**
     * Starts all steps in sequence asynchronously, skipping already-done steps.
     * If force=true, resets all done flags first.
     * @return true if started; false if already running
     */
    boolean runAll(RunAllRequest request, Consumer<String> onLog, Consumer<Integer> onDone);
}
