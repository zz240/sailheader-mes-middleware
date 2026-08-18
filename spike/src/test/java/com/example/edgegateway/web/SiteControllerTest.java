package com.example.edgegateway.web;

import com.example.edgegateway.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SiteController.class)
class SiteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyService apiKeyService;

    @Test
    void generatesApiKey() throws Exception {
        when(apiKeyService.generate(1L)).thenReturn("raw-key-once");

        mockMvc.perform(post("/api/sites/1/api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("raw-key-once"));
    }
}
