package com.moviesearch.service.impl;

import com.moviesearch.service.PythonScriptRunner;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class PythonScriptRunnerImpl implements PythonScriptRunner {

    @FunctionalInterface
    interface ProcessLauncher {
        Process launch(List<String> cmd, Map<String, String> extraEnv) throws IOException;
    }

    private final ProcessLauncher processLauncher;

    public PythonScriptRunnerImpl() {
        this((cmd, extraEnv) -> {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().putAll(extraEnv);
            return pb.start();
        });
    }

    PythonScriptRunnerImpl(ProcessLauncher processLauncher) {
        this.processLauncher = processLauncher;
    }

    @Override
    public void run(Path script, Consumer<String> onLog) throws IOException, InterruptedException {
        run(script, onLog, Map.of());
    }

    @Override
    public void run(Path script, Consumer<String> onLog, Map<String, String> extraEnv) throws IOException, InterruptedException {
        Process process = processLauncher.launch(List.of("python", script.toString()), extraEnv);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) onLog.accept(line);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new IOException("Script failed with exit code: " + exitCode);
    }
}
