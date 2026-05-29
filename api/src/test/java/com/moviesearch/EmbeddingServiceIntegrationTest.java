package com.moviesearch;

import com.moviesearch.config.TritonGrpcConfig;
import com.moviesearch.config.TritonProperties;
import com.moviesearch.service.EmbeddingService;
import com.moviesearch.service.QueryTokenizer;
import com.moviesearch.service.impl.EmbeddingServiceImpl;
import com.moviesearch.service.impl.HuggingFaceQueryTokenizer;
import com.moviesearch.service.impl.MockEmbeddingService;
import com.moviesearch.service.impl.MockQueryTokenizer;
import inference.GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EmbeddingServiceIntegrationTest {

    private static final Path REAL_TOKENIZER_PATH =
        Paths.get("..", "model-repository", "all-minilm-l6-v2", "1", "tokenizer.json")
            .toAbsolutePath().normalize();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TritonGrpcConfig.class,
                    EmbeddingServiceImpl.class,
                    HuggingFaceQueryTokenizer.class,
                    MockEmbeddingService.class,
                    MockQueryTokenizer.class)
            .withBean(TritonProperties.class, () -> {
                TritonProperties p = new TritonProperties();
                p.setTokenizerPath(REAL_TOKENIZER_PATH.toString());
                return p;
            });

    private void assumeTokenizerAvailable() {
        assumeTrue(Files.exists(REAL_TOKENIZER_PATH),
            "Skipping: tokenizer.json not found at " + REAL_TOKENIZER_PATH
                + ". Run `./start-services.sh --rebuild` (or load-model) to populate it.");
    }

    @Test
    void defaultConfig_embeddingServiceImplIsActive() {
        assumeTokenizerAvailable();
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EmbeddingService.class);
            assertThat(ctx.getBean(EmbeddingService.class)).isInstanceOf(EmbeddingServiceImpl.class);
            assertThat(ctx).hasSingleBean(QueryTokenizer.class);
            assertThat(ctx.getBean(QueryTokenizer.class)).isInstanceOf(HuggingFaceQueryTokenizer.class);
        });
    }

    @Test
    void defaultConfig_managedChannelAndStubPresent() {
        assumeTokenizerAvailable();
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ManagedChannel.class);
            assertThat(ctx).hasSingleBean(GRPCInferenceServiceBlockingStub.class);
        });
    }

    @Test
    void mockFalse_embeddingServiceImplIsActive() {
        assumeTokenizerAvailable();
        contextRunner
                .withPropertyValues("triton.mock=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmbeddingService.class);
                    assertThat(ctx.getBean(EmbeddingService.class)).isInstanceOf(EmbeddingServiceImpl.class);
                });
    }

    @Test
    void mockFalse_managedChannelAndStubPresent() {
        assumeTokenizerAvailable();
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
                    assertThat(ctx).hasSingleBean(QueryTokenizer.class);
                    assertThat(ctx.getBean(QueryTokenizer.class)).isInstanceOf(MockQueryTokenizer.class);
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
        assumeTokenizerAvailable();
        contextRunner.run(ctx -> {
            TritonProperties props = ctx.getBean(TritonProperties.class);
            assertThat(props.getHost()).isEqualTo("localhost");
            assertThat(props.getPort()).isEqualTo(8001);
            assertThat(props.getModelName()).isEqualTo("all-MiniLM-L6-v2");
            assertThat(props.getDeadlineMs()).isEqualTo(5000L);
            assertThat(props.getMaxLength()).isEqualTo(128);
        });
    }
}
