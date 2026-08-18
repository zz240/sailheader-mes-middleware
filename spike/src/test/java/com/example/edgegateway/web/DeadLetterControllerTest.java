package com.example.edgegateway.web;

import com.example.edgegateway.model.DeadLetter;
import com.example.edgegateway.service.DeadLetterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeadLetterController.class)
class DeadLetterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeadLetterService deadLetterService;

    private DeadLetter deadLetter(Long id, Long jobId) {
        DeadLetter deadLetter = new DeadLetter();
        deadLetter.setId(id);
        deadLetter.setJobId(jobId);
        deadLetter.setActionIndex(3);
        deadLetter.setInputJson("{\"order_no\":\"PO-1\"}");
        deadLetter.setError("HTTP 500");
        deadLetter.setStatus("FAILED");
        return deadLetter;
    }

    @Test
    void listsDeadLetters() throws Exception {
        when(deadLetterService.list(7L, "FAILED")).thenReturn(List.of(deadLetter(1L, 7L)));

        mockMvc.perform(get("/api/dead-letters").param("jobId", "7").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(7))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void getsDeadLetterDetail() throws Exception {
        when(deadLetterService.get(1L)).thenReturn(deadLetter(1L, 7L));

        mockMvc.perform(get("/api/dead-letters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputJson").value("{\"order_no\":\"PO-1\"}"))
                .andExpect(jsonPath("$.error").value("HTTP 500"));
    }
}
