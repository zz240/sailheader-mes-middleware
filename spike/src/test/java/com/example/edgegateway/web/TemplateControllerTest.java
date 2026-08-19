package com.example.edgegateway.web;

import com.example.edgegateway.model.Template;
import com.example.edgegateway.service.TemplateService;
import com.example.edgegateway.web.dto.TemplateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TemplateController.class)
class TemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TemplateService templateService;

    private Template template(Long id, String name, String version) {
        Template template = new Template();
        template.setId(id);
        template.setName(name);
        template.setVersion(version);
        template.setConfigJson(Map.of("job", Map.of("name", name)));
        return template;
    }

    @Test
    void listsTemplates() throws Exception {
        when(templateService.list(null)).thenReturn(List.of(template(1L, "po-sync", "1")));

        mockMvc.perform(get("/api/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("po-sync"))
                .andExpect(jsonPath("$.data[0].version").value("1"));
    }

    @Test
    void createsTemplate() throws Exception {
        when(templateService.create(any(TemplateRequest.class))).thenReturn(template(1L, "po-sync", "1"));

        mockMvc.perform(post("/api/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"po-sync\",\"configJson\":{\"job\":{\"name\":\"po-sync\"}}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value("1"));
    }

    @Test
    void exportsAndImports() throws Exception {
        when(templateService.get(1L)).thenReturn(template(1L, "po-sync", "1"));

        mockMvc.perform(get("/api/templates/1/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1"))
                .andExpect(jsonPath("$.data.configJson.job.name").value("po-sync"));

        when(templateService.importTemplate(any(TemplateRequest.class))).thenReturn(template(2L, "po-sync", "2"));
        mockMvc.perform(post("/api/templates/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"po-sync\",\"configJson\":{\"job\":{\"name\":\"po-sync\"}}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value("2"));
    }

    @Test
    void updatesAndDeletes() throws Exception {
        when(templateService.update(eq(1L), any(TemplateRequest.class))).thenReturn(template(1L, "po-sync", "1"));
        mockMvc.perform(put("/api/templates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"po-sync\",\"configJson\":{\"job\":{\"name\":\"po-sync\"}}}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/templates/1")).andExpect(status().isNoContent());
        verify(templateService).delete(1L);
    }
}
