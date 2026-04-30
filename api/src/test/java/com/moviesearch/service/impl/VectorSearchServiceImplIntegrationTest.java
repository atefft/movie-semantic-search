package com.moviesearch.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {"qdrant.mock=false", "triton.mock=true"})
class VectorSearchServiceImplIntegrationTest {

    private static final String QDRANT_URL =
            "http://localhost:6333/collections/movies/points/search";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @AfterEach
    void tearDown() {
        server.verify();
        server.reset();
    }

    @Test
    void search_happyPath_returnsTwoResults() throws Exception {
        String qdrantResponse = """
                {
                  "result": [
                    {"id":1,"score":0.94,"payload":{"title":"Cast Away","release_year":2000,"genres":["Drama","Adventure"],"summary_snippet":"A FedEx executive must survive a crash landing.","thumbnail_url":"https://image.tmdb.org/t/p/w200/path.jpg"}},
                    {"id":2,"score":0.91,"payload":{"title":"The Martian","release_year":2015,"genres":["Science Fiction"],"summary_snippet":"An astronaut stranded on Mars.","thumbnail_url":null}}
                  ]
                }
                """;

        server.expect(requestTo(QDRANT_URL))
              .andRespond(withSuccess(qdrantResponse, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/search").param("q", "survival"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.results[0].title").value("Cast Away"))
               .andExpect(jsonPath("$.results[0].year").value(2000))
               .andExpect(jsonPath("$.results[0].score").value(0.94))
               .andExpect(jsonPath("$.results[0].summarySnippet").isNotEmpty())
               .andExpect(jsonPath("$.results[1].thumbnailUrl").value((Object) null));
    }

    @Test
    void search_emptyResults_returnsZeroCount() throws Exception {
        server.expect(requestTo(QDRANT_URL))
              .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/search").param("q", "xyz"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.count").value(0))
               .andExpect(jsonPath("$.results").isArray())
               .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    void search_qdrantUnreachable_returns503WithConnectionRefused() throws Exception {
        server.expect(requestTo(QDRANT_URL))
              .andRespond(withException(new IOException("Connection refused")));

        mockMvc.perform(get("/api/search").param("q", "foo"))
               .andExpect(status().isServiceUnavailable())
               .andExpect(jsonPath("$.detail").value(containsString("connection refused")));
    }

    @Test
    void search_qdrantNon2xx_returns503WithErrorResponse() throws Exception {
        server.expect(requestTo(QDRANT_URL))
              .andRespond(withServerError());

        mockMvc.perform(get("/api/search").param("q", "foo"))
               .andExpect(status().isServiceUnavailable())
               .andExpect(jsonPath("$.detail").value(containsString("error response")));
    }
}
