package com.moviesearch.config;

import inference.GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TritonGrpcConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TritonGrpcConfig.class, TritonProperties.class);

    @Test
    void bothBeansRegisteredWhenMockAbsent() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ManagedChannel.class);
            assertThat(ctx).hasSingleBean(GRPCInferenceServiceBlockingStub.class);
        });
    }

    @Test
    void bothBeansRegisteredWhenMockFalse() {
        contextRunner
                .withPropertyValues("triton.mock=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ManagedChannel.class);
                    assertThat(ctx).hasSingleBean(GRPCInferenceServiceBlockingStub.class);
                });
    }

    @Test
    void noBeansRegisteredWhenMockTrue() {
        contextRunner
                .withPropertyValues("triton.mock=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ManagedChannel.class);
                    assertThat(ctx).doesNotHaveBean(GRPCInferenceServiceBlockingStub.class);
                });
    }

    @Test
    void preDestroyShutsDownChannel() throws Exception {
        ManagedChannel mockChannel = mock(ManagedChannel.class);
        when(mockChannel.shutdown()).thenReturn(mockChannel);
        when(mockChannel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);

        TritonGrpcConfig config = new TritonGrpcConfig();
        var field = TritonGrpcConfig.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(config, mockChannel);

        config.shutdown();

        verify(mockChannel).shutdown();
        verify(mockChannel).awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
