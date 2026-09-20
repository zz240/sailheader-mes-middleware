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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
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
        when(messageHistoryService.listRuns(anyLong(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(run)));

        mockMvc.perform(get("/api/history/runs").param("jobId", "7").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].jobId").value(7));
    }

    /** #129：业务键是这个接口新接的一格，得原样传下去（口径是「任一消息命中即保留整运行」）。 */
    @Test
    void passesBusinessKeyToRunList() throws Exception {
        when(messageHistoryService.listRuns(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 0L, "records", List.of()));

        mockMvc.perform(get("/api/history/runs").param("businessKey", "SO-2026-0917-0042"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        verify(messageHistoryService).listRuns(nullable(Long.class), nullable(String.class),
                eq("SO-2026-0917-0042"), eq(1), eq(20));
    }

    /**
     * #130：计数条与运行摘要接口跟 `/history/runs` **同一组筛选参数**原样传下去
     * （多一个参数或少传一个，计数与列表就是两套口径）。
     */
    @Test
    void passesRunFiltersToSummary() throws Exception {
        when(messageHistoryService.runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("runs", 3L, "messages", 5L, "steps", 9L, "failedSteps", 1L,
                        "summaries", List.of(Map.of("runId", 1L, "messageCount", 2L, "steps", 4L,
                                "failedSteps", 1L, "matchedBusinessKeys", List.of("SO-1")))));

        mockMvc.perform(get("/api/history/runs/summary").param("jobId", "7").param("status", "FAILED")
                        .param("businessKey", "SO-2026-0917-0042").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs").value(3))
                .andExpect(jsonPath("$.data.messages").value(5))
                .andExpect(jsonPath("$.data.steps").value(9))
                .andExpect(jsonPath("$.data.failedSteps").value(1))
                .andExpect(jsonPath("$.data.summaries[0].runId").value(1))
                .andExpect(jsonPath("$.data.summaries[0].failedSteps").value(1))
                .andExpect(jsonPath("$.data.summaries[0].matchedBusinessKeys[0]").value("SO-1"));

        // page/size 只决定摘要给哪一页（计数与它们无关），照样原样传；默认值是 1 / 20
        verify(messageHistoryService).runsSummary(eq(7L), eq("FAILED"), eq("SO-2026-0917-0042"), eq(2), eq(50));
    }

    /** 不传分页时按默认 1 / 20（与运行列表同一套默认值）。 */
    @Test
    void summaryDefaultsToFirstPage() throws Exception {
        when(messageHistoryService.runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("runs", 0L, "messages", 0L, "steps", 0L, "failedSteps", 0L,
                        "summaries", List.of()));

        mockMvc.perform(get("/api/history/runs/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs").value(0));

        verify(messageHistoryService).runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), eq(1), eq(20));
    }

    /** 路径别被 `/runs/{id}` 抢走：`summary` 不是数字，仍要落到聚合这一条上。 */
    @Test
    void summaryPathIsNotSwallowedByRunDetail() throws Exception {
        when(messageHistoryService.runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("runs", 0L, "messages", 0L, "steps", 0L, "failedSteps", 0L,
                        "summaries", List.of()));

        mockMvc.perform(get("/api/history/runs/summary").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaries").isEmpty());
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

    /** #136：消息步列表带上所属运行的 jobId（左列表每行要写「任务 #id」）。 */
    @Test
    void carriesRunJobIdInStepList() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(2L);
        step.setStatus("FAILED");
        step.setJobId(7L);
        when(messageHistoryService.listSteps(anyLong(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(step)));

        mockMvc.perform(get("/api/history/steps").param("jobId", "7").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].jobId").value(7));
    }

    @Test
    void listsHistoryRootAsSteps() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(1L);
        step.setStatus("SUCCESS");
        when(messageHistoryService.listSteps(anyLong(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(step)));

        mockMvc.perform(get("/api/history").param("jobId", "7").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(1));
    }

    @Test
    void getsHistoryDetailAsStep() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(9L);
        step.setStatus("FAILED");
        when(messageHistoryService.getStep(9L)).thenReturn(step);

        mockMvc.perform(get("/api/history/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
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
