package com.example.edgegateway.web;

import com.example.edgegateway.mock.MockMesController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 业务码信封：成功包 {code,message,data}、mock 裸响应、非法 JSON → PARAM_INVALID 信封。 */
@WebMvcTest({SampleController.class, MockMesController.class})
class ApiEnvelopeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successResponseIsWrapped() throws Exception {
        mockMvc.perform(post("/api/sample-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample\":{\"order_no\":\"PO-1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data[0]").value("order_no"));
    }

    @Test
    void mockEndpointsStayRaw() throws Exception {
        mockMvc.perform(post("/mock/mes/purchase-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.message").value("ok"));
    }

    @Test
    void malformedJsonReturnsParamInvalidEnvelope() throws Exception {
        mockMvc.perform(post("/api/sample-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
    }
}
