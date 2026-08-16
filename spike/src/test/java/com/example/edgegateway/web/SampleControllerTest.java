package com.example.edgegateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SampleController.class)
class SampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void extractsFieldPathsFromSample() throws Exception {
        mockMvc.perform(post("/api/sample-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample\":{\"order_no\":\"PO-1\","
                                + "\"data\":{\"items\":[{\"name\":\"x\"}]}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("order_no"))
                .andExpect(jsonPath("$[1]").value("data.items[0].name"));
    }
}
