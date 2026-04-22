package com.moviesearch.service.impl;

import com.moviesearch.model.MovieResult;
import com.moviesearch.model.VectorSearchRequest;
import com.moviesearch.model.VectorSearchResponse;
import com.moviesearch.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MockVectorSearchService implements VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(MockVectorSearchService.class);

    private static final List<MovieResult> MOCK_RESULTS = List.of(
        MovieResult.builder()
            .title("Cast Away")
            .year(2000)
            .genres(List.of("Drama", "Adventure"))
            .score(0.94f)
            .summarySnippet("A FedEx executive must transform himself physically and emotionally to survive a crash landing on a deserted island.")
            .thumbnailUrl(null)
            .build(),
        MovieResult.builder()
            .title("The Martian")
            .year(2015)
            .genres(List.of("Science Fiction", "Adventure", "Drama"))
            .score(0.91f)
            .summarySnippet("An astronaut becomes stranded on Mars after his team assumes him dead, and must rely on his ingenuity to survive.")
            .thumbnailUrl(null)
            .build(),
        MovieResult.builder()
            .title("Gravity")
            .year(2013)
            .genres(List.of("Science Fiction", "Thriller"))
            .score(0.88f)
            .summarySnippet("Two astronauts work together to survive after an accident leaves them stranded in space.")
            .thumbnailUrl(null)
            .build(),
        MovieResult.builder()
            .title("Interstellar")
            .year(2014)
            .genres(List.of("Science Fiction", "Adventure", "Drama"))
            .score(0.85f)
            .summarySnippet("A team of explorers travel through a wormhole in space in an attempt to ensure humanity's survival.")
            .thumbnailUrl(null)
            .build(),
        MovieResult.builder()
            .title("Apollo 13")
            .year(1995)
            .genres(List.of("Drama", "History", "Thriller"))
            .score(0.82f)
            .summarySnippet("NASA must devise a strategy to return Apollo 13 to Earth safely after the spacecraft undergoes massive internal damage.")
            .thumbnailUrl(null)
            .build()
    );

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        log.debug("VectorSearchService.search called [limit={}]", request.getLimit());

        int limit = Math.min(request.getLimit(), MOCK_RESULTS.size());
        List<MovieResult> results = MOCK_RESULTS.subList(0, limit);

        log.debug("VectorSearchService.search returning {} results [requested={}]",
            results.size(), request.getLimit());

        return new VectorSearchResponse(results);
    }
}
