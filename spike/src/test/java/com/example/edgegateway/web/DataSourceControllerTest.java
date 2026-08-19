package com.example.edgegateway.web;

import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.service.BusinessCode;
import com.example.edgegateway.service.BusinessException;
import com.example.edgegateway.service.ConnectionTester;
import com.example.edgegateway.service.DataSourceService;
import com.example.edgegateway.web.dto.DataSourceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DataSourceController.class)
class DataSourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSourceService dataSourceService;

    @MockBean
    private ConnectionTester connectionTester;

    private static String body(String name) {
        return "{\"name\":\"" + name + "\",\"siteId\":1,\"type\":\"HTTP\","
                + "\"address\":\"http://localhost:8080/mock\",\"addition\":{\"method\":\"GET\"}}";
    }

    @Test
    void createsDataSource() throws Exception {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setName("erp");
        ds.setSiteId(1L);
        ds.setType(DataSourceType.HTTP);
        ds.setAddress("http://localhost:8080/mock");
        ds.setAddition(Map.of("method", "GET"));
        when(dataSourceService.create(any(DataSourceRequest.class))).thenReturn(ds);

        mockMvc.perform(post("/api/data-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("erp")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("erp"));
    }

    @Test
    void rejectsInvalidDataSource() throws Exception {
        when(dataSourceService.create(any(DataSourceRequest.class)))
                .thenThrow(new IllegalArgumentException("数据源名称不能为空"));

        mockMvc.perform(post("/api/data-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateDataSource() throws Exception {
        when(dataSourceService.create(any(DataSourceRequest.class)))
                .thenThrow(new BusinessException(BusinessCode.DUPLICATE_NAME, "数据源名称已存在"));

        mockMvc.perform(post("/api/data-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dup")))
                .andExpect(status().isConflict());
    }

    @Test
    void listsDataSourcesWithFilter() throws Exception {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setName("erp");
        ds.setSiteId(1L);
        ds.setType(DataSourceType.HTTP);
        when(dataSourceService.list(any(), any())).thenReturn(List.of(ds));

        mockMvc.perform(get("/api/data-sources").param("name", "erp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("erp"));
    }

    @Test
    void testsConnection() throws Exception {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setType(DataSourceType.HTTP);
        when(dataSourceService.get(1L)).thenReturn(ds);
        when(connectionTester.test(ds)).thenReturn(new ConnectionTester.TestResult(true, "HTTP 200"));

        mockMvc.perform(post("/api/data-sources/1/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    void deletesDataSource() throws Exception {
        mockMvc.perform(delete("/api/data-sources/1"))
                .andExpect(status().isNoContent());
    }
}
