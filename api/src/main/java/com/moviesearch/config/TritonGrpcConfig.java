package com.moviesearch.config;

import inference.GRPCInferenceServiceGrpc;
import inference.GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class TritonGrpcConfig {

    private ManagedChannel channel;

    @Bean
    @ConditionalOnProperty(name = "triton.mock", havingValue = "false", matchIfMissing = true)
    public ManagedChannel tritonChannel(TritonProperties props) {
        channel = ManagedChannelBuilder.forAddress(props.getHost(), props.getPort())
                .usePlaintext()
                .build();
        return channel;
    }

    @Bean
    @ConditionalOnProperty(name = "triton.mock", havingValue = "false", matchIfMissing = true)
    public GRPCInferenceServiceBlockingStub tritonStub(ManagedChannel channel) {
        return GRPCInferenceServiceGrpc.newBlockingStub(channel);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
