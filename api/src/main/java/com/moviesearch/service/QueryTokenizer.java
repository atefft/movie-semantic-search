package com.moviesearch.service;

import com.moviesearch.model.TokenizedInput;

public interface QueryTokenizer {

    TokenizedInput tokenize(String text);
}
