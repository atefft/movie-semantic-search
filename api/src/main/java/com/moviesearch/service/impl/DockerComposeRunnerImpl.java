package com.moviesearch.service.impl;

import com.moviesearch.config.ProjectProperties;
import com.moviesearch.service.DockerComposeRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class DockerComposeRunnerImpl implements DockerComposeRunner {

    @FunctionalInterface
    interface ProcessLauncher {
        Process launch(List<String> cmd, Path workingDir) throws IOException;
    }

    private final ProjectProperties projectProperties;
    private final ProcessLauncher processLauncher;

    public DockerComposeRunnerImpl(ProjectProperties projectProperties) {
        this(projectProperties,
                (cmd, dir) -> new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start());
    }

    DockerComposeRunnerImpl(ProjectProperties projectProperties, ProcessLauncher processLauncher) {
        this.projectProperties = projectProperties;
        this.processLauncher = processLauncher;
    }

    @Override
    public void run(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("compose");
        for (String arg : args) cmd.add(arg);
        Path workingDir = projectProperties.getRoot();
        Process process = processLauncher.launch(cmd, workingDir);
        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new IOException("docker compose exited with code: " + exitCode);
    }
}
