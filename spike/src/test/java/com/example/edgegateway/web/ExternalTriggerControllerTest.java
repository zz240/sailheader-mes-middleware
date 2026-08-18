package com.example.edgegateway.web;

import com.example.edgegateway.model.Job;
import com.example.edgegateway.service.ExternalTriggerService;
import com.example.edgegateway.service.UnauthorizedException;
import com.example.edgegateway.web.dto.JobView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalTriggerController.class)
class ExternalTriggerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExternalTriggerService triggerService;

    private JobView view() {
        Job job = new Job();
        job.setId(1L);
        job.setSiteId(5L);
        job.setName("po-sync");
        job.setStatus(1);
        return JobView.from(job);
    }

    @Test
    void triggersWithValidApiKey() throws Exception {
        when(triggerService.trigger(eq(1L), eq("valid-key"), any())).thenReturn(view());

        mockMvc.perform(post("/api/external/jobs/1/trigger")
                        .header("X-API-Key", "valid-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("po-sync"));
    }

    @Test
    void returns401WhenKeyInvalid() throws Exception {
        when(triggerService.trigger(eq(1L), eq("bad-key"), any()))
                .thenThrow(new UnauthorizedException("API Key 缺失或无效"));

        mockMvc.perform(post("/api/external/jobs/1/trigger")
                        .header("X-API-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
