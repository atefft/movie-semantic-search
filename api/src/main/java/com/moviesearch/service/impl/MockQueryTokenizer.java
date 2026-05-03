package com.moviesearch.service.impl;

import com.moviesearch.config.TritonProperties;
import com.moviesearch.model.TokenizedInput;
import com.moviesearch.service.QueryTokenizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "triton.mock", havingValue = "true")
public class MockQueryTokenizer implements QueryTokenizer {

    private final int maxLength;

    public MockQueryTokenizer(TritonProperties props) {
        this.maxLength = props.getMaxLength();
    }

    @Override
    public TokenizedInput tokenize(String text) {
        long[] inputIds = new long[maxLength];
        long[] attentionMask = new long[maxLength];
        long[] tokenTypeIds = new long[maxLength];
        int filled = Math.min(text.length(), maxLength);
        for (int i = 0; i < filled; i++) {
            inputIds[i] = (long) text.charAt(i);
            attentionMask[i] = 1L;
        }
        return new TokenizedInput(inputIds, attentionMask, tokenTypeIds);
    }
}
