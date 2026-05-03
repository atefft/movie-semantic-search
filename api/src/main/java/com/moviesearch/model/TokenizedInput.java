package com.moviesearch.model;

public final class TokenizedInput {

    private final long[] inputIds;
    private final long[] attentionMask;
    private final long[] tokenTypeIds;

    public TokenizedInput(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
        if (inputIds.length != attentionMask.length || inputIds.length != tokenTypeIds.length) {
            throw new IllegalArgumentException(
                "TokenizedInput arrays must have equal length: inputIds=" + inputIds.length
                    + ", attentionMask=" + attentionMask.length
                    + ", tokenTypeIds=" + tokenTypeIds.length);
        }
        this.inputIds = inputIds;
        this.attentionMask = attentionMask;
        this.tokenTypeIds = tokenTypeIds;
    }

    public long[] getInputIds() {
        return inputIds;
    }

    public long[] getAttentionMask() {
        return attentionMask;
    }

    public long[] getTokenTypeIds() {
        return tokenTypeIds;
    }

    public int length() {
        return inputIds.length;
    }
}
