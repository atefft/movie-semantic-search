package com.moviesearch.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Validated
@ConfigurationProperties(prefix = "project")
public class ProjectProperties {

    @NotBlank
    private String root;

    public Path getRoot() {
        return Path.of(root);
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
