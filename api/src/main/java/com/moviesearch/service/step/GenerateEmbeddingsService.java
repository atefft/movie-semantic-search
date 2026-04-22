package com.moviesearch.service.step;

import java.util.function.Consumer;

public interface GenerateEmbeddingsService {
    void execute(Consumer<String> onLog, boolean force);
}
