package com.moviesearch.controller;

import com.moviesearch.exception.TritonServiceException;
import com.moviesearch.model.ServiceStatus;
import com.moviesearch.service.PipelineService;
import com.moviesearch.service.QdrantServiceManager;
import com.moviesearch.service.TritonServiceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperatorController.class)
class OperatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PipelineService pipelineService;

    @MockBean
    private TritonServiceManager tritonManager;

    @MockBean
    private QdrantServiceManager qdrantManager;

    @Test
    void health_bothStopped() throws Exception {
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.STOPPED);
        when(qdrantManager.getStatus()).thenReturn(ServiceStatus.STOPPED);

        mockMvc.perform(get("/api/operator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triton").value(false))
            .andExpect(jsonPath("$.qdrant").value(false))
            .andExpect(jsonPath("$.mock").value(true));
    }

    @Test
    void health_tritonRunning() throws Exception {
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        when(qdrantManager.getStatus()).thenReturn(ServiceStatus.STOPPED);

        mockMvc.perform(get("/api/operator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triton").value(true))
            .andExpect(jsonPath("$.qdrant").value(false));
    }

    @Test
    void health_bothRunning() throws Exception {
        when(tritonManager.getStatus()).thenReturn(ServiceStatus.RUNNING);
        when(qdrantManager.getStatus()).thenReturn(ServiceStatus.RUNNING);

        mockMvc.perform(get("/api/operator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triton").value(true))
            .andExpect(jsonPath("$.qdrant").value(true));
    }

    @Test
    void startService_triton() throws Exception {
        mockMvc.perform(post("/api/operator/service/triton/start"))
            .andExpect(status().isOk());

        verify(tritonManager).start();
    }

    @Test
    void startService_qdrant() throws Exception {
        mockMvc.perform(post("/api/operator/service/qdrant/start"))
            .andExpect(status().isOk());

        verify(qdrantManager).start();
    }

    @Test
    void stopService_triton() throws Exception {
        mockMvc.perform(post("/api/operator/service/triton/stop"))
            .andExpect(status().isOk());

        verify(tritonManager).stop();
    }

    @Test
    void stopService_qdrant() throws Exception {
        mockMvc.perform(post("/api/operator/service/qdrant/stop"))
            .andExpect(status().isOk());

        verify(qdrantManager).stop();
    }

    @Test
    void startService_unknownName() throws Exception {
        mockMvc.perform(post("/api/operator/service/foo/start"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void stopService_unknownName() throws Exception {
        mockMvc.perform(post("/api/operator/service/foo/stop"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void startService_tritonThrows() throws Exception {
        doThrow(new TritonServiceException(TritonServiceException.START_FAILED))
            .when(tritonManager).start();

        mockMvc.perform(post("/api/operator/service/triton/start"))
            .andExpect(status().isInternalServerError());
    }
}
