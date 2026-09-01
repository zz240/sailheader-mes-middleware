package com.example.edgegateway.web;

import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.service.MessageHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HistoryController.class)
class HistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageHistoryService messageHistoryService;

    @Test
    void listsRuns() throws Exception {
        JobRun run = new JobRun();
        run.setId(1L);
        run.setJobId(7L);
        run.setStatus("SUCCESS");
        when(messageHistoryService.listRuns(anyLong(), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(run)));

        mockMvc.perform(get("/api/history/runs").param("jobId", "7").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].jobId").value(7));
    }

    @Test
    void getsRunDetail() throws Exception {
        JobRun run = new JobRun();
        run.setId(1L);
        run.setJobId(7L);
        run.setStatus("SUCCESS");
        when(messageHistoryService.getRun(1L)).thenReturn(run);

        mockMvc.perform(get("/api/history/runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void defaultsStepsToFailed() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(2L);
        step.setStatus("FAILED");
        when(messageHistoryService.listSteps(anyLong(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(step)));

        mockMvc.perform(get("/api/history/steps").param("jobId", "7").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].status").value("FAILED"));
    }

    @Test
    void redeliversFailedStep() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(5L);
        step.setStatus("REDELIVERED");
        when(messageHistoryService.redeliver(5L)).thenReturn(step);

        mockMvc.perform(post("/api/history/steps/5/redeliver"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REDELIVERED"))
                .andExpect(jsonPath("$.data.stepId").value(5));
    }
}
