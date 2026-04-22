package com.moviesearch.service.impl;

import com.moviesearch.service.PythonScriptRunner;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Service
public class PythonScriptRunnerImpl implements PythonScriptRunner {

    @FunctionalInterface
    interface ProcessLauncher {
        Process launch(List<String> cmd) throws IOException;
    }

    private final ProcessLauncher processLauncher;

    public PythonScriptRunnerImpl() {
        this(cmd -> new ProcessBuilder(cmd).redirectErrorStream(true).start());
    }

    PythonScriptRunnerImpl(ProcessLauncher processLauncher) {
        this.processLauncher = processLauncher;
    }

    @Override
    public void run(Path script, Consumer<String> onLog) throws IOException, InterruptedException {
        Process process = processLauncher.launch(List.of("python", script.toString()));
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
