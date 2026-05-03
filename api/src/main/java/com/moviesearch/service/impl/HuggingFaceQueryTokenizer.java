package com.moviesearch.service.impl;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.moviesearch.config.TritonProperties;
import com.moviesearch.model.TokenizedInput;
import com.moviesearch.service.QueryTokenizer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "triton.mock", havingValue = "false", matchIfMissing = true)
public class HuggingFaceQueryTokenizer implements QueryTokenizer {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceQueryTokenizer.class);

    private final HuggingFaceTokenizer tokenizer;
    private final int maxLength;

    public HuggingFaceQueryTokenizer(TritonProperties props) {
        this.maxLength = props.getMaxLength();
        Path path = Paths.get(props.getTokenizerPath());
        Map<String, String> options = new HashMap<>();
        options.put("padding", "max_length");
        options.put("truncation", "true");
        options.put("maxLength", String.valueOf(maxLength));
        try {
            this.tokenizer = HuggingFaceTokenizer.newInstance(path, options);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to load HuggingFace tokenizer from " + path
                    + ". Confirm the model-repository tokenizer files are mounted into the api container.", e);
        }
        log.info("HuggingFaceQueryTokenizer initialized [tokenizerPath={}, maxLength={}]", path, maxLength);
    }

    @Override
    public TokenizedInput tokenize(String text) {
        Encoding encoding = tokenizer.encode(text);
        long[] inputIds = padOrTruncate(encoding.getIds(), maxLength);
        long[] attentionMask = padOrTruncate(encoding.getAttentionMask(), maxLength);
        long[] typeIdsRaw = encoding.getTypeIds();
        long[] tokenTypeIds = typeIdsRaw == null
            ? new long[maxLength]
            : padOrTruncate(typeIdsRaw, maxLength);
        return new TokenizedInput(inputIds, attentionMask, tokenTypeIds);
    }

    private static long[] padOrTruncate(long[] src, int target) {
        if (src.length == target) {
            return src;
        }
        long[] out = new long[target];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, target));
        return out;
    }

    @PreDestroy
    void close() {
        if (tokenizer != null) {
            tokenizer.close();
        }
    }
}
