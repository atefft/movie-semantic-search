package com.moviesearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProjectPropertiesTestConfig.class);

    @Test
    void absolutePathBindsCorrectly() {
        runner.withPropertyValues("project.root=/tmp/test")
                .run(ctx -> {
                    ProjectProperties props = ctx.getBean(ProjectProperties.class);
                    assertThat(props.getRoot()).isEqualTo(Path.of("/tmp/test"));
                });
    }

    @Test
    void relativePathBindsCorrectly() {
        runner.withPropertyValues("project.root=.")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ProjectProperties props = ctx.getBean(ProjectProperties.class);
                    assertThat(props.getRoot()).isEqualTo(Path.of("."));
                });
    }

    @Test
    void missingRootCausesBindValidationException() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(ProjectProperties.class)
    static class ProjectPropertiesTestConfig {
    }
}
