package com.moviesearch;

import com.moviesearch.config.TritonGrpcConfig;
import com.moviesearch.config.TritonProperties;
import com.moviesearch.service.EmbeddingService;
import com.moviesearch.service.impl.EmbeddingServiceImpl;
import com.moviesearch.service.impl.MockEmbeddingService;
import inference.GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingServiceIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TritonGrpcConfig.class,
                    TritonProperties.class,
                    EmbeddingServiceImpl.class,
                    MockEmbeddingService.class);

    @Test
    void defaultConfig_embeddingServiceImplIsActive() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EmbeddingService.class);
            assertThat(ctx.getBean(EmbeddingService.class)).isInstanceOf(EmbeddingServiceImpl.class);
        });
    }

    @Test
    void defaultConfig_managedChannelAndStubPresent() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ManagedChannel.class);
            assertThat(ctx).hasSingleBean(GRPCInferenceServiceBlockingStub.class);
        });
    }

    @Test
    void mockFalse_embeddingServiceImplIsActive() {
        contextRunner
                .withPropertyValues("triton.mock=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmbeddingService.class);
                    assertThat(ctx.getBean(EmbeddingService.class)).isInstanceOf(EmbeddingServiceImpl.class);
                });
    }

    @Test
    void mockFalse_managedChannelAndStubPresent() {
        contextRunner
                .withPropertyValues("triton.mock=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ManagedChannel.class);
                    assertThat(ctx).hasSingleBean(GRPCInferenceServiceBlockingStub.class);
                });
    }

    @Test
    void mockTrue_mockEmbeddingServiceIsActive() {
        contextRunner
                .withPropertyValues("triton.mock=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmbeddingService.class);
                    assertThat(ctx.getBean(EmbeddingService.class)).isInstanceOf(MockEmbeddingService.class);
                });
    }

    @Test
    void mockTrue_noManagedChannelOrStubInContext() {
        contextRunner
                .withPropertyValues("triton.mock=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ManagedChannel.class);
                    assertThat(ctx).doesNotHaveBean(GRPCInferenceServiceBlockingStub.class);
                });
    }

    @Test
    void tritonProperties_defaultsMatchApplicationYml() {
        contextRunner.run(ctx -> {
            TritonProperties props = ctx.getBean(TritonProperties.class);
            assertThat(props.getHost()).isEqualTo("localhost");
            assertThat(props.getPort()).isEqualTo(8001);
            assertThat(props.getModelName()).isEqualTo("all-MiniLM-L6-v2");
            assertThat(props.getDeadlineMs()).isEqualTo(5000L);
        });
    }
}
