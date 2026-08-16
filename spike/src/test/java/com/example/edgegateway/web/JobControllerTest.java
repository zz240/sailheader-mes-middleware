package com.example.edgegateway.web;

import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.WatermarkType;
import com.example.edgegateway.service.ActionService;
import com.example.edgegateway.service.ConflictException;
import com.example.edgegateway.service.JobExecutionService;
import com.example.edgegateway.service.JobService;
import com.example.edgegateway.service.RouteRegistry;
import com.example.edgegateway.web.dto.ActionRequest;
import com.example.edgegateway.web.dto.JobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({JobController.class, ActionController.class})
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobService jobService;

    @MockBean
    private ActionService actionService;

    @MockBean
    private RouteRegistry routeRegistry;

    @MockBean
    private JobExecutionService jobExecutionService;

    private static final String JOB_BODY =
            "{\"name\":\"po-sync\",\"siteId\":1,\"status\":0,"
                    + "\"crontab\":\"0 0/30 * * * ?\",\"timezone\":\"Asia/Shanghai\","
                    + "\"retryCount\":3,\"backoffMs\":1000,"
                    + "\"watermarkType\":\"SEQUENCE\",\"watermarkColumn\":\"id\",\"remarks\":\"备注\"}";

    @Test
    void createsJob() throws Exception {
        Job job = new Job();
        job.setId(1L);
        job.setName("po-sync");
        job.setWatermarkType(WatermarkType.SEQUENCE);
        when(jobService.create(any(JobRequest.class))).thenReturn(job);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("po-sync"));
    }

    @Test
    void rejectsInvalidJob() throws Exception {
        when(jobService.create(any(JobRequest.class)))
                .thenThrow(new IllegalArgumentException("任务名称不能为空"));

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateJob() throws Exception {
        when(jobService.create(any(JobRequest.class)))
                .thenThrow(new ConflictException("任务名称已存在"));

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void setsStatus() throws Exception {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        when(jobService.setStatus(1L, 1)).thenReturn(job);

        mockMvc.perform(put("/api/jobs/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1));
    }

    @Test
    void listsJobsWithFilter() throws Exception {
        Job job = new Job();
        job.setId(1L);
        job.setName("po-sync");
        when(jobService.list(any(), any())).thenReturn(java.util.List.of(job));

        mockMvc.perform(get("/api/jobs").param("name", "po"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("po-sync"));
    }

    @Test
    void addsActionToJob() throws Exception {
        Action action = new Action();
        action.setId(1L);
        action.setJobId(1L);
        action.setName("read");
        action.setType(ActionType.REMOTE_ACCESS);
        action.setActionIndex(1);
        when(actionService.add(any(), any(ActionRequest.class))).thenReturn(action);

        mockMvc.perform(post("/api/jobs/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"read\",\"type\":\"REMOTE_ACCESS\",\"index\":1,\"addition\":{}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.index").value(1));
    }

    @Test
    void rejectsDuplicateActionIndex() throws Exception {
        when(actionService.add(any(), any(ActionRequest.class)))
                .thenThrow(new IllegalArgumentException("同任务下动作序号不能重复"));

        mockMvc.perform(post("/api/jobs/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"read\",\"type\":\"REMOTE_ACCESS\",\"index\":1,\"addition\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesActionIndex() throws Exception {
        Action action = new Action();
        action.setId(1L);
        action.setJobId(1L);
        action.setName("map");
        action.setType(ActionType.FIELD_MAPPING);
        action.setActionIndex(3);
        when(actionService.update(any(), any(ActionRequest.class))).thenReturn(action);

        mockMvc.perform(put("/api/actions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"map\",\"type\":\"FIELD_MAPPING\",\"index\":3,\"addition\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(3));
    }

    @Test
    void deletesAction() throws Exception {
        mockMvc.perform(delete("/api/actions/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void executesJob() throws Exception {
        Job job = new Job();
        job.setId(1L);
        job.setName("po-sync");
        job.setLastRunStatus("SUCCESS");
        when(jobExecutionService.execute(any(), any())).thenReturn(job);

        mockMvc.perform(post("/api/jobs/1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"PO-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRunStatus").value("SUCCESS"));
    }

    @Test
    void rejectsExecuteWhenJobDisabled() throws Exception {
        when(jobExecutionService.execute(any(), any()))
                .thenThrow(new IllegalArgumentException("任务未启用，无法执行"));

        mockMvc.perform(post("/api/jobs/1/execute"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesJob() throws Exception {
        mockMvc.perform(delete("/api/jobs/1"))
                .andExpect(status().isNoContent());
    }
}
