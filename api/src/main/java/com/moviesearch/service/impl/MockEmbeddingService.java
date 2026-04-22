package com.moviesearch.service.impl;

import com.moviesearch.model.EmbeddingRequest;
import com.moviesearch.model.EmbeddingResponse;
import com.moviesearch.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MockEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(MockEmbeddingService.class);
    private static final int VECTOR_DIM = 384;

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        String text = request.getText();
        String textPreview = text.length() > 50 ? text.substring(0, 50) : text;
        log.debug("EmbeddingService.embed called [text='{}']", textPreview);

        float[] vector = new float[VECTOR_DIM];
        for (int i = 0; i < VECTOR_DIM; i++) {
            vector[i] = (i % 2 == 0) ? 0.1f : -0.1f;
        }

        log.debug("EmbeddingService.embed returning vector [dimensions={}]", vector.length);
        return new EmbeddingResponse(vector);
    }
}
