package com.moviesearch.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

public interface PythonScriptRunner {
    void run(Path script, Consumer<String> onLog) throws IOException, InterruptedException;
    void run(Path script, Consumer<String> onLog, Map<String, String> extraEnv) throws IOException, InterruptedException;
}
