package com.moviesearch.model;

import lombok.Value;

@Value
public class OperatorHealthResponse {
    boolean triton;
    boolean qdrant;
    boolean mock;
}
