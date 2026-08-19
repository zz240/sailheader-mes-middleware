package com.example.edgegateway.web;

import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.service.DataSourceService;
import com.example.edgegateway.service.StructureProbeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StructureProbeController.class)
class StructureProbeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StructureProbeService probeService;

    @MockBean
    private DataSourceService dataSourceService;

    @Test
    void probesSampleAndReturnsStructure() throws Exception {
        when(probeService.probeSample(any())).thenReturn(new SourceStructure("sample",
                List.of(new SourceField("order_no", "STRING", true, List.of("PO-1")))));

        mockMvc.perform(post("/api/probe/sample")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample\":{\"order_no\":\"PO-1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceKey").value("sample"))
                .andExpect(jsonPath("$.data.fields[0].name").value("order_no"));
    }

    @Test
    void fallsBackToSampleWhenDbProbeFailsAndSampleProvided() throws Exception {
        when(dataSourceService.get(1L)).thenReturn(new DataSource());
        when(probeService.probeFromDb(any(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("连接失败"));
        when(probeService.probeSample(any())).thenReturn(new SourceStructure("sample",
                List.of(new SourceField("order_no", "STRING", true, List.of("PO-1")))));

        mockMvc.perform(post("/api/data-sources/1/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"table\":\"po_order\",\"sample\":{\"order_no\":\"PO-1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceKey").value("sample"));
    }

    @Test
    void suggestsTableFromJobReadSql() throws Exception {
        when(probeService.suggestTable(1L)).thenReturn("po_order");

        mockMvc.perform(get("/api/data-sources/1/suggest-table"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.table").value("po_order"));
    }
}
