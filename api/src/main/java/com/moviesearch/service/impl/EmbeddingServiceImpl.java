package com.moviesearch.service.impl;

import com.google.protobuf.ByteString;
import com.moviesearch.config.TritonProperties;
import com.moviesearch.exception.EmbeddingServiceException;
import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import com.moviesearch.model.TokenizedInput;
import com.moviesearch.service.EmbeddingService;
import com.moviesearch.service.QueryTokenizer;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "triton.mock", havingValue = "false", matchIfMissing = true)
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceImpl.class);
    private static final int VECTOR_DIM = 384;
    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String OUTPUT_TOKEN_EMBEDDINGS = "token_embeddings";
    private static final String INT64 = "INT64";

    private final GRPCInferenceServiceBlockingStub stub;
    private final QueryTokenizer tokenizer;
    private final TritonProperties props;

    public EmbeddingServiceImpl(GRPCInferenceServiceBlockingStub stub,
                                QueryTokenizer tokenizer,
                                TritonProperties props) {
        this.stub = stub;
        this.tokenizer = tokenizer;
        this.props = props;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        String text = request.getText();
        String textPreview = text.length() > 50 ? text.substring(0, 50) : text;
        log.debug("EmbeddingService.embed called [text='{}']", textPreview);

        TokenizedInput tokens = tokenizer.tokenize(text);
        int seqLen = tokens.length();

        ModelInferRequest grpcRequest = ModelInferRequest.newBuilder()
            .setModelName(props.getModelName())
            .addInputs(buildIntInput(INPUT_IDS, seqLen, tokens.getInputIds()))
            .addInputs(buildIntInput(ATTENTION_MASK, seqLen, tokens.getAttentionMask()))
            .addInputs(buildIntInput(TOKEN_TYPE_IDS, seqLen, tokens.getTokenTypeIds()))
            .addOutputs(ModelInferRequest.InferRequestedOutputTensor.newBuilder()
                .setName(OUTPUT_TOKEN_EMBEDDINGS))
            .build();

        ModelInferResponse response;
        try {
            response = stub
                .withDeadlineAfter(props.getDeadlineMs(), TimeUnit.MILLISECONDS)
                .modelInfer(grpcRequest);
        } catch (StatusRuntimeException e) {
            throw mapException(e);
        }

        float[] tokenEmbeddings = readFp32Output(response, seqLen);
        int actual = tokenEmbeddings.length;
        int expected = seqLen * VECTOR_DIM;
        if (actual != expected) {
            throw new EmbeddingServiceException(EmbeddingServiceException.invalidVectorDimension(actual));
        }

        float[] vector = meanPool(tokenEmbeddings, tokens.getAttentionMask(), seqLen);

        log.debug("EmbeddingService.embed returning vector [dimensions={}]", vector.length);
        return new EmbeddingResponse(vector);
    }

    private static ModelInferRequest.InferInputTensor buildIntInput(String name, int seqLen, long[] values) {
        InferTensorContents.Builder contents = InferTensorContents.newBuilder();
        List<Long> boxed = new ArrayList<>(values.length);
        for (long v : values) {
            boxed.add(v);
        }
        contents.addAllInt64Contents(boxed);
        return ModelInferRequest.InferInputTensor.newBuilder()
            .setName(name)
            .setDatatype(INT64)
            .addShape(1).addShape(seqLen)
            .setContents(contents)
            .build();
    }

    /**
     * Triton's gRPC server returns FP32 outputs in {@code raw_output_contents} (binary, little-endian)
     * by default. The typed {@code outputs[i].contents.fp32_contents} list is used only when the server
     * is configured otherwise. Read whichever channel is populated.
     */
    private static float[] readFp32Output(ModelInferResponse response, int seqLen) {
        if (response.getRawOutputContentsCount() > 0) {
            ByteString bytes = response.getRawOutputContents(0);
            int floatCount = bytes.size() / Float.BYTES;
            float[] out = new float[floatCount];
            ByteBuffer buf = bytes.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < floatCount; i++) {
                out[i] = buf.getFloat();
            }
            return out;
        }
        List<Float> typed = response.getOutputs(0).getContents().getFp32ContentsList();
        float[] out = new float[typed.size()];
        for (int i = 0; i < typed.size(); i++) {
            out[i] = typed.get(i);
        }
        return out;
    }

    private static float[] meanPool(float[] tokenEmbeddings, long[] attentionMask, int seqLen) {
        float[] pooled = new float[VECTOR_DIM];
        long maskSum = 0;
        for (int t = 0; t < seqLen; t++) {
            long m = attentionMask[t];
            if (m == 0L) {
                continue;
            }
            maskSum += m;
            int rowOffset = t * VECTOR_DIM;
            for (int d = 0; d < VECTOR_DIM; d++) {
                pooled[d] += tokenEmbeddings[rowOffset + d] * m;
            }
        }
        float divisor = (float) Math.max(maskSum, 1L);
        for (int d = 0; d < VECTOR_DIM; d++) {
            pooled[d] /= divisor;
        }
        return pooled;
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
