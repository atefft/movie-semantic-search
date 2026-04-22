package com.moviesearch.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

public interface PythonScriptRunner {
    void run(Path script, Consumer<String> onLog) throws IOException, InterruptedException;
}
