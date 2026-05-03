package com.moviesearch.service.impl;

import com.moviesearch.config.TritonProperties;
import com.moviesearch.model.TokenizedInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockQueryTokenizerTest {

    @Test
    void tokenize_returnsArraysOfMaxLength() {
        TritonProperties props = new TritonProperties();
        props.setMaxLength(16);
        MockQueryTokenizer tokenizer = new MockQueryTokenizer(props);

        TokenizedInput out = tokenizer.tokenize("hello");

        assertThat(out.getInputIds()).hasSize(16);
        assertThat(out.getAttentionMask()).hasSize(16);
        assertThat(out.getTokenTypeIds()).hasSize(16);
    }

    @Test
    void tokenize_attentionMaskCoversTextOnly() {
        TritonProperties props = new TritonProperties();
        props.setMaxLength(16);
        MockQueryTokenizer tokenizer = new MockQueryTokenizer(props);

        long[] mask = tokenizer.tokenize("hello").getAttentionMask();

        for (int i = 0; i < 5; i++) {
            assertThat(mask[i]).isEqualTo(1L);
        }
        for (int i = 5; i < 16; i++) {
            assertThat(mask[i]).isEqualTo(0L);
        }
    }

    @Test
    void tokenize_truncatesTextLongerThanMaxLength() {
        TritonProperties props = new TritonProperties();
        props.setMaxLength(4);
        MockQueryTokenizer tokenizer = new MockQueryTokenizer(props);

        TokenizedInput out = tokenizer.tokenize("hello world");

        assertThat(out.length()).isEqualTo(4);
        for (long m : out.getAttentionMask()) {
            assertThat(m).isEqualTo(1L);
        }
    }

    @Test
    void tokenize_tokenTypeIdsAlwaysZero() {
        TritonProperties props = new TritonProperties();
        props.setMaxLength(16);
        MockQueryTokenizer tokenizer = new MockQueryTokenizer(props);

        long[] types = tokenizer.tokenize("hello").getTokenTypeIds();

        for (long t : types) {
            assertThat(t).isEqualTo(0L);
        }
    }
}
