package com.moviesearch.controller;

import com.moviesearch.model.OperatorHealthResponse;
import com.moviesearch.model.PipelineStep;
import com.moviesearch.model.PipelineStatusResponse;
import com.moviesearch.model.RunAllRequest;
import com.moviesearch.model.RunStepRequest;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.PipelineService;
import com.moviesearch.service.QdrantServiceManager;
import com.moviesearch.service.TritonServiceManager;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    private final PipelineService pipelineService;
    private final TritonServiceManager tritonManager;
    private final QdrantServiceManager qdrantManager;

    public OperatorController(PipelineService pipelineService,
                              TritonServiceManager tritonManager,
                              QdrantServiceManager qdrantManager) {
        this.pipelineService = pipelineService;
        this.tritonManager = tritonManager;
        this.qdrantManager = qdrantManager;
    }

    @GetMapping("/health")
    public OperatorHealthResponse health() {
        return new OperatorHealthResponse(
            tritonManager.getStatus() == ServiceStatus.RUNNING,
            qdrantManager.getStatus() == ServiceStatus.RUNNING,
            true);
    }

    @GetMapping("/status")
    public PipelineStatusResponse status() {
        return pipelineService.getStatus();
    }

    @GetMapping(value = "/run/{step}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> runStep(@PathVariable int step) {
        PipelineStep pipelineStep;
        try {
            pipelineStep = PipelineStep.fromNumber(step);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        SseEmitter emitter = new SseEmitter(60_000L);
        boolean started = pipelineService.runStep(new RunStepRequest(pipelineStep),
            line -> emitLine(emitter, line),
            code -> emitDone(emitter, code));
        if (!started) return ResponseEntity.status(409).build();
        return ResponseEntity.ok(emitter);
    }

    @GetMapping(value = "/run/all", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> runAll(@RequestParam(defaultValue = "false") boolean force) {
        SseEmitter emitter = new SseEmitter(300_000L);
        boolean started = pipelineService.runAll(new RunAllRequest(force),
            line -> emitLine(emitter, line),
            code -> emitDone(emitter, code));
        if (!started) return ResponseEntity.status(409).build();
        return ResponseEntity.ok(emitter);
    }

    @PostMapping("/service/{name}/start")
    public ResponseEntity<Void> startService(@PathVariable String name) {
        if ("triton".equals(name)) tritonManager.start();
        else if ("qdrant".equals(name)) qdrantManager.start();
        else return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/service/{name}/stop")
    public ResponseEntity<Void> stopService(@PathVariable String name) {
        if ("triton".equals(name)) tritonManager.stop();
        else if ("qdrant".equals(name)) qdrantManager.stop();
        else return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().build();
    }

    private void emitLine(SseEmitter emitter, String line) {
        try {
            emitter.send(SseEmitter.event().data(line));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void emitDone(SseEmitter emitter, int exitCode) {
        try {
            emitter.send(SseEmitter.event().name("done").data("{\"exitCode\":" + exitCode + "}"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
