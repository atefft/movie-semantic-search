package com.moviesearch.service.impl;

import com.google.protobuf.ByteString;
import com.moviesearch.config.TritonProperties;
import com.moviesearch.exception.EmbeddingServiceException;
import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import com.moviesearch.service.EmbeddingService;
import inference.GRPCInferenceServiceGrpc.GRPCInferenceServiceBlockingStub;
import inference.Inference.InferTensorContents;
import inference.Inference.ModelInferRequest;
import inference.Inference.ModelInferResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "triton.mock", havingValue = "false", matchIfMissing = true)
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceImpl.class);
    private static final int VECTOR_DIM = 384;

    private final GRPCInferenceServiceBlockingStub stub;
    private final TritonProperties props;

    public EmbeddingServiceImpl(GRPCInferenceServiceBlockingStub stub, TritonProperties props) {
        this.stub = stub;
        this.props = props;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        String text = request.getText();
        String textPreview = text.length() > 50 ? text.substring(0, 50) : text;
        log.debug("EmbeddingService.embed called [text='{}']", textPreview);

        ModelInferRequest grpcRequest = ModelInferRequest.newBuilder()
            .setModelName(props.getModelName())
            .addInputs(ModelInferRequest.InferInputTensor.newBuilder()
                .setName("TEXT")
                .setDatatype("BYTES")
                .addShape(1).addShape(1)
                .setContents(InferTensorContents.newBuilder()
                    .addBytesContents(ByteString.copyFromUtf8(text))))
            .addOutputs(ModelInferRequest.InferRequestedOutputTensor.newBuilder()
                .setName("sentence_embedding"))
            .build();

        ModelInferResponse response;
        try {
            response = stub
                .withDeadlineAfter(props.getDeadlineMs(), TimeUnit.MILLISECONDS)
                .modelInfer(grpcRequest);
        } catch (StatusRuntimeException e) {
            throw mapException(e);
        }

        List<Float> floats = response.getOutputs(0).getContents().getFp32ContentsList();
        if (floats.size() != VECTOR_DIM) {
            throw new EmbeddingServiceException(EmbeddingServiceException.invalidVectorDimension(floats.size()));
        }

        float[] vector = new float[VECTOR_DIM];
        for (int i = 0; i < VECTOR_DIM; i++) {
            vector[i] = floats.get(i);
        }

        log.debug("EmbeddingService.embed returning vector [dimensions={}]", vector.length);
        return new EmbeddingResponse(vector);
    }

    private EmbeddingServiceException mapException(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        return switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED -> new EmbeddingServiceException(
                EmbeddingServiceException.CONNECTION_REFUSED, e);
            case INVALID_ARGUMENT -> new EmbeddingServiceException(
                EmbeddingServiceException.INVALID_REQUEST, e);
            default -> new EmbeddingServiceException(
                EmbeddingServiceException.CONNECTION_REFUSED, e);
        };
    }
}
