package com.example.edgegateway.web;

import com.example.edgegateway.mapping.suggest.MappingSuggestion;
import com.example.edgegateway.mapping.suggest.MatchSource;
import com.example.edgegateway.mapping.suggest.TransformKind;
import com.example.edgegateway.service.MappingSuggestionService;
import com.example.edgegateway.web.dto.SuggestionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SuggestionController.class)
class SuggestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MappingSuggestionService suggestionService;

    @Test
    void returnsSuggestions() throws Exception {
        when(suggestionService.suggest(any(SuggestionRequest.class))).thenReturn(List.of(
                new MappingSuggestion("order_no", "sheet_no", 1.0,
                        TransformKind.STRING, null, null, null, MatchSource.EXACT)));

        mockMvc.perform(post("/api/mapping-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":1,\"source\":{\"sourceKey\":\"sample\","
                                + "\"fields\":[{\"name\":\"order_no\",\"type\":\"STRING\","
                                + "\"nullable\":true,\"samples\":[\"PO-1\"]}]},"
                                + "\"targets\":[{\"name\":\"sheet_no\",\"required\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourcePath").value("order_no"))
                .andExpect(jsonPath("$[0].targetName").value("sheet_no"))
                .andExpect(jsonPath("$[0].matchSource").value("EXACT"));
    }
}
