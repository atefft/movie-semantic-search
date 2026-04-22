package com.moviesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "dataset")
public class DatasetProperties {

    private String corpusUrl;
    private Path dataDir;

    public String getCorpusUrl() {
        return corpusUrl;
    }

    public void setCorpusUrl(String corpusUrl) {
        this.corpusUrl = corpusUrl;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }
}
