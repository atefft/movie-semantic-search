package com.moviesearch.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.moviesearch.config.TritonProperties;
import com.moviesearch.exception.EmbeddingServiceException;
import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import com.moviesearch.model.TokenizedInput;
import com.moviesearch.service.QueryTokenizer;
import inference.GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub;
import inference.Inference.InferTensorContents;
import inference.Inference.ModelInferRequest;
import inference.Inference.ModelInferResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceImplTest {

    private static final int VECTOR_DIM = 384;
    private static final int SEQ_LEN = 128;

    @Mock
    private GRPCInferenceServiceBlockingStub stub;

    @Mock
    private QueryTokenizer tokenizer;

    private TritonProperties props;
    private EmbeddingServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new TritonProperties();
        service = new EmbeddingServiceImpl(stub, tokenizer, props);
    }

    private TokenizedInput tokenizedInput(int activeTokens) {
        long[] inputIds = new long[SEQ_LEN];
        long[] mask = new long[SEQ_LEN];
        long[] types = new long[SEQ_LEN];
        for (int i = 0; i < activeTokens; i++) {
            inputIds[i] = 100L + i;
            mask[i] = 1L;
        }
        return new TokenizedInput(inputIds, mask, types);
    }

    /** Constant-valued token embeddings — pooled value should equal that constant for any mask. */
    private ModelInferResponse buildConstantResponse(float value) {
        InferTensorContents.Builder contents = InferTensorContents.newBuilder();
        for (int t = 0; t < SEQ_LEN; t++) {
            for (int d = 0; d < VECTOR_DIM; d++) {
                contents.addFp32Contents(value);
            }
        }
        return wrap(contents);
    }

    /** Per-token embeddings where token t has every dim set to (t+1)f. Pooled value depends on mask. */
    private ModelInferResponse buildPerTokenResponse() {
        InferTensorContents.Builder contents = InferTensorContents.newBuilder();
        for (int t = 0; t < SEQ_LEN; t++) {
            float v = (float) (t + 1);
            for (int d = 0; d < VECTOR_DIM; d++) {
                contents.addFp32Contents(v);
            }
        }
        return wrap(contents);
    }

    private ModelInferResponse buildResponseOfSize(int totalFloats) {
        InferTensorContents.Builder contents = InferTensorContents.newBuilder();
        for (int i = 0; i < totalFloats; i++) {
            contents.addFp32Contents(0.0f);
        }
        return wrap(contents);
    }

    private ModelInferResponse wrap(InferTensorContents.Builder contents) {
        ModelInferResponse.InferOutputTensor output = ModelInferResponse.InferOutputTensor.newBuilder()
            .setName("token_embeddings")
            .setDatatype("FP32")
            .addShape(1).addShape(SEQ_LEN).addShape(VECTOR_DIM)
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
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildConstantResponse(1.0f));

        EmbeddingResponse response = service.embed(new EmbeddingRequest("hello world"));

        assertThat(response.getVector()).hasSize(VECTOR_DIM);
    }

    @Test
    void embed_constantTokenEmbeddings_pooledMatchesConstant() {
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(5));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildConstantResponse(0.5f));

        float[] vector = service.embed(new EmbeddingRequest("hello")).getVector();

        for (float v : vector) {
            assertThat(v).isEqualTo(0.5f);
        }
    }

    @Test
    void embed_meanPoolingHonorsAttentionMask() {
        // mask covers tokens 0..2 (values 1, 2, 3). Tokens 3..127 have value 4..128 but mask=0.
        // Expected mean: (1+2+3)/3 = 2.0 across all 384 dims.
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildPerTokenResponse());

        float[] vector = service.embed(new EmbeddingRequest("hello")).getVector();

        for (float v : vector) {
            assertThat(v).isEqualTo(2.0f);
        }
    }

    @Test
    void embed_buildsRequestWithThreeInt64InputsAndCorrectShapes() {
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildConstantResponse(0.0f));

        ArgumentCaptor<ModelInferRequest> captor = ArgumentCaptor.forClass(ModelInferRequest.class);
        service.embed(new EmbeddingRequest("hello"));

        org.mockito.Mockito.verify(stub).modelInfer(captor.capture());
        ModelInferRequest req = captor.getValue();
        assertThat(req.getModelName()).isEqualTo(props.getModelName());
        assertThat(req.getInputsCount()).isEqualTo(3);

        List<String> inputNames = req.getInputsList().stream()
            .map(ModelInferRequest.InferInputTensor::getName).toList();
        assertThat(inputNames).containsExactly("input_ids", "attention_mask", "token_type_ids");

        for (ModelInferRequest.InferInputTensor t : req.getInputsList()) {
            assertThat(t.getDatatype()).isEqualTo("INT64");
            assertThat(t.getShapeList()).containsExactly(1L, (long) SEQ_LEN);
            assertThat(t.getContents().getInt64ContentsCount()).isEqualTo(SEQ_LEN);
        }

        assertThat(req.getOutputsCount()).isEqualTo(1);
        assertThat(req.getOutputs(0).getName()).isEqualTo("token_embeddings");
    }

    @Test
    void embed_logExitMessage() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(EmbeddingServiceImpl.class);
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingServiceImpl.class);
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildConstantResponse(0.0f));

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
    void embed_logEntryMessage() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(EmbeddingServiceImpl.class);
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingServiceImpl.class);
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildConstantResponse(0.0f));

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
    void embed_longTextTruncatedInLog() {
        ListAppender<ILoggingEvent> appender = attachLogAppender(EmbeddingServiceImpl.class);
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingServiceImpl.class);
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildConstantResponse(0.0f));

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
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.CONNECTION_REFUSED);
    }

    @Test
    void embed_grpcDeadlineExceeded_throwsConnectionRefused() {
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.CONNECTION_REFUSED);
    }

    @Test
    void embed_grpcInvalidArgument_throwsInvalidRequest() {
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INVALID_ARGUMENT));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.INVALID_REQUEST);
    }

    @Test
    void embed_grpcUnknownStatus_throwsConnectionRefused() {
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.CONNECTION_REFUSED);
    }

    // --- Invalid response size ---

    @Test
    void embed_unexpectedResponseSize_throwsException() {
        when(tokenizer.tokenize(any())).thenReturn(tokenizedInput(3));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.modelInfer(any(ModelInferRequest.class))).thenReturn(buildResponseOfSize(100));

        assertThatThrownBy(() -> service.embed(new EmbeddingRequest("hello")))
            .isInstanceOf(EmbeddingServiceException.class)
            .hasMessage(EmbeddingServiceException.invalidVectorDimension(100));
    }
}
