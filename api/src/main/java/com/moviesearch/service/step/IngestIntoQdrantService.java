package com.moviesearch.service.step;

import java.util.function.Consumer;

public interface IngestIntoQdrantService {
    void execute(Consumer<String> onLog, boolean force);
}
