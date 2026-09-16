package com.example.edgegateway.web;

import com.example.edgegateway.mock.MockMesController;
import com.example.edgegateway.service.FieldMappingEditorService;
import com.example.edgegateway.service.MappingDraftValidationService;
import com.example.edgegateway.service.MappingTrialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务码信封：成功包 {code,message,data}、mock 裸响应、非法 JSON → PARAM_INVALID 信封。
 *
 * <p>拿「校验配置」那个只读端点当样本（它只要一个没有别的依赖的服务，且不碰数据库）——原来那个
 * 「贴样例拿字段路径」的 {@code POST /api/sample-fields} 已经随 #118 收掉了（#107 删掉旧编辑器之后
 * 再没有调用方），信封这一层与具体是哪个端点无关。</p>
 */
@WebMvcTest({FieldMappingEditorController.class, MockMesController.class})
@Import(MappingDraftValidationService.class)
class ApiEnvelopeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FieldMappingEditorService editorService;

    /** 同一个控制器上还挂着试算那个端点（#116），上下文里得有这么个 bean（这一层不验它的行为）。 */
    @MockBean
    private MappingTrialService trialService;

    @Test
    void successResponseIsWrapped() throws Exception {
        mockMvc.perform(post("/api/field-mapping-editor/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft":{"rules":[{"sourceStep":1,"source":"order_no",
                                  "target":"order_no","transform":"STRING"}]},
                                 "earlierActionIndexes":[1],"defaultSourceStep":1,"actionIndex":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.valid").value(true));
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
        mockMvc.perform(post("/api/field-mapping-editor/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
    }
}
