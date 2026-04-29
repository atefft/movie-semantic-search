package com.moviesearch.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.moviesearch.config.TritonProperties;
import com.moviesearch.exception.EmbeddingServiceException;
import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import inference.GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub;
import inference.Inference.InferTensorContents;
import inference.Inference.ModelInferRequest;
import inference.Inference.ModelInferResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceImplTest {

    @Mock
    private GRPCInferenceServiceBlockingStub stub;

    private TritonProperties props;
    private EmbeddingServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new TritonProperties();
        service = new EmbeddingServiceImpl(stub, props);
    }

    private ModelInferResponse buildResponse(int dims) {
        InferTensorContents.Builder contents = InferTensorContents.newBuilder();
        for (int i = 0; i < dims; i++) {
            contents.addFp32Contents(0.1f * i);
        }
        ModelInferResponse.InferOutputTensor output = ModelInferResponse.InferOutputTensor.newBuilder()
            .setName("sentence_embedding")
            .setDatatype("FP32")
            .addShape(1).addShape(dims)
            .setContents(contents)
            .build();
        return ModelInferResponse.newBuilder().addOutputs(output).build();
    }

    private ListAppender<ILoggingEvent> attachLogAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // --- Happy paths ---

    @Test
    void embed_returnsCorrectDimensions() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildResponse(384));

        EmbeddingResponse response = service.embed(new EmbeddingRequest("hello world"));

        assertThat(response.getVector()).hasSize(384);
    }

    @Test
    void embed_vectorValuesMatchResponse() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildResponse(384));

        float[] vector = service.embed(new EmbeddingRequest("hello world")).getVector();

        assertThat(vector[0]).isEqualTo(0.0f);
        assertThat(vector[1]).isEqualTo(0.1f);
        assertThat(vector[2]).isEqualTo(0.2f);
    }

    @Test
    void embed_logEntryMessage() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(EmbeddingServiceImpl.class);
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingServiceImpl.class);
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildResponse(384));

        try {
            service.embed(new EmbeddingRequest("hello world"));
            List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).contains("EmbeddingService.embed called [text='hello world']");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void embed_logExitMessage() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(EmbeddingServiceImpl.class);
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingServiceImpl.class);
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildResponse(384));

        try {
            service.embed(new EmbeddingRequest("hello world"));
            List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).contains("EmbeddingService.embed returning vector [dimensions=384]");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void embed_longTextTruncatedInLog() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(EmbeddingServiceImpl.class);
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingServiceImpl.class);
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildResponse(384));

        String longText = "a".repeat(100);
        try {
            service.embed(new EmbeddingRequest(longText));
            List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            String expected = "EmbeddingService.embed called [text='" + "a".repeat(50) + "']";
            assertThat(messages).contains(expected);
        } finally {
            logger.detachAppender(appender);
        }
    }

    // --- gRPC status mapping ---

    @Test
    void embed_grpcUnavailable_throwsConnectionRefused() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.CONNECTION_REFUSED);
    }

    @Test
    void embed_grpcDeadlineExceeded_throwsConnectionRefused() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.CONNECTION_REFUSED);
    }

    @Test
    void embed_grpcInvalidArgument_throwsInvalidRequest() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INVALID_ARGUMENT));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.INVALID_REQUEST);
    }

    @Test
    void embed_grpcUnknownStatus_throwsConnectionRefused() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.CONNECTION_REFUSED);
    }

    // --- Invalid vector dimension ---

    @Test
    void embed_wrongDimension_throwsException() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildResponse(100));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.invalidVectorDimension(100));
    }
}
