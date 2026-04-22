package com.moviesearch.model;

import lombok.Value;
import java.util.List;

@Value
public class PipelineStatusResponse {
    List<StepStatus> steps;
}
