package com.moviesearch.model;

import lombok.Value;

@Value
public class StepStatus {
    PipelineStep step;
    boolean done;
    boolean prereqsMet;
    boolean running;
}
