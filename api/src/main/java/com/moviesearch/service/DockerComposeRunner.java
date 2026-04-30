package com.moviesearch.service;

import java.io.IOException;

public interface DockerComposeRunner {
    void run(String... args) throws IOException, InterruptedException;
}
