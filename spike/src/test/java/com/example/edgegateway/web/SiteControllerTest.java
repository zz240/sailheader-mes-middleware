package com.example.edgegateway.web;

import com.example.edgegateway.service.ApiKeyService;
import com.example.edgegateway.mapper.SiteMapper;
import com.example.edgegateway.model.Site;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SiteController.class)
class SiteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private SiteMapper siteMapper;

    @Test
    void generatesApiKey() throws Exception {
        when(apiKeyService.generate(1L)).thenReturn("raw-key-once");

        mockMvc.perform(post("/api/sites/1/api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("raw-key-once"));
    }

    @Test
    void listsSitesWithoutExposingApiKey() throws Exception {
        Site site = new Site();
        site.setId(1L);
        site.setName("演示客户");
        site.setApiKey("abc123");
        when(siteMapper.selectList(any())).thenReturn(java.util.List.of(site));

        mockMvc.perform(get("/api/sites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("演示客户"))
                .andExpect(jsonPath("$[0].hasApiKey").value(true))
                .andExpect(jsonPath("$[0].apiKey").doesNotExist());
    }
}
