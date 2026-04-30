package com.moviesearch;

import com.moviesearch.config.DatasetProperties;
import com.moviesearch.config.ModelProperties;
import com.moviesearch.config.ProjectProperties;
import com.moviesearch.config.QdrantProperties;
import com.moviesearch.config.TmdbProperties;
import com.moviesearch.config.TritonProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({DatasetProperties.class, ModelProperties.class, TritonProperties.class, ProjectProperties.class, QdrantProperties.class, TmdbProperties.class})
public class MovieSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieSearchApplication.class, args);
    }
}
