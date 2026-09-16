package com.example.edgegateway.web;

import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.service.FieldMappingEditorService;
import com.example.edgegateway.service.MappingDraftValidationService;
import com.example.edgegateway.web.dto.FieldMappingEditorView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「校验配置」的 HTTP 契约（#103）：字段名和信封形状是前端那一边认的东西，锁在这里。
 *
 * <p>校验逻辑本身用真的那个服务（它没有别的依赖，只用一个 ObjectMapper），
 * 这个测试只管「请求进得来、结果按信封出得去」。</p>
 */
@WebMvcTest(FieldMappingEditorController.class)
@Import(MappingDraftValidationService.class)
class FieldMappingEditorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FieldMappingEditorService editorService;

    @Test
    void returnsProblemsAndCompiledArtifactsForADraft() throws Exception {
        mockMvc.perform(post("/api/field-mapping-editor/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft":{"rules":[{"sourceStep":1,"source":"bomNo","target":"bom.bomNo",
                                  "transform":"STRING"}]},
                                 "earlierActionIndexes":[1],"defaultSourceStep":1,"actionIndex":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.errors").isEmpty())
                .andExpect(jsonPath("$.data.warnings").isEmpty())
                .andExpect(jsonPath("$.data.compiled.jsonata")
                        .value("{\n  \"bom\": {\n  \"bomNo\": $s_1.bomNo\n  }\n}"))
                .andExpect(jsonPath("$.data.compiled.rulesJson").value(containsString("\"bom.bomNo\"")));
    }

    @Test
    void returnsEachProblemWithTheRowItBelongsTo() throws Exception {
        mockMvc.perform(post("/api/field-mapping-editor/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft":{"rules":[{"sourceStep":1,"source":"items","target":"bom.bomLines",
                                  "transform":"STRING","iterate":"FOR_EACH",
                                  "children":[{"target":"materialCode","transform":"STRING"}]}]},
                                 "earlierActionIndexes":[1],"actionIndex":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0].target").value("materialCode"))
                .andExpect(jsonPath("$.data.errors[0].parent").value("bom.bomLines"))
                .andExpect(jsonPath("$.data.errors[0].message").value("映射规则缺少来源字段 source"));
    }

    /**
     * 非法日期格式串从编辑器这条路上回来的是**错误**（#111 验收二）：编辑器据此在保存前拦下。
     *
     * <p>拦它的是与保存那道门同一份结构校验实现（{@code FieldMappingConfig.problems()}）——保存那一半在
     * {@code ActionServiceTest} 里断言，这里管的是「编辑器发出去的那一份请求拿得回这条错误」。</p>
     */
    @Test
    void reportsAnIllegalDatePatternAsAnError() throws Exception {
        mockMvc.perform(post("/api/field-mapping-editor/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft":{"rules":[{"sourceStep":1,"source":"createdTime",
                                  "target":"bom.createdDate","transform":"DATE","datePattern":"ISO-8601"}]},
                                 "earlierActionIndexes":[1],"defaultSourceStep":1,"actionIndex":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0].target").value("bom.createdDate"))
                .andExpect(jsonPath("$.data.errors[0].message")
                        .value(containsString("日期格式串不合法: ISO-8601")));
    }

    @Test
    void reportsAMissingSourceStepAsAWarningNotAnError() throws Exception {
        mockMvc.perform(post("/api/field-mapping-editor/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft":{"rules":[{"source":"bomNo","target":"bom.bomNo","transform":"STRING"}]},
                                 "earlierActionIndexes":[1],"defaultSourceStep":1,"actionIndex":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.errors").isEmpty())
                .andExpect(jsonPath("$.data.warnings[0].target").value("bom.bomNo"))
                .andExpect(jsonPath("$.data.warnings[0].message").value(containsString("当成任务输入")));
    }

    /**
     * 「粘贴 JSON」那条路的 HTTP 契约（#115）：与同路径的 GET 并列，请求体里带样例原文与来源步骤。
     *
     * <p>字段名（`step` / `json`）与回来的 SourceSample 形状是前端那一边认的东西，锁在这里。</p>
     */
    @Test
    void returnsTheFieldListOfAPastedSample() throws Exception {
        String json = "{\"order_no\":\"PO-1\"}";
        when(editorService.sourceFieldsFromSample(3L, 1, json)).thenReturn(
                new FieldMappingEditorView.SourceSample(1, "读订单", "paste", false,
                        List.of(new SourceField("order_no", "STRING", true, List.of("PO-1"))), json));

        mockMvc.perform(post("/api/jobs/3/field-mapping-editor/source-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"step":1,"json":"{\\"order_no\\":\\"PO-1\\"}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.step").value(1))
                .andExpect(jsonPath("$.data.stepName").value("读订单"))
                .andExpect(jsonPath("$.data.sampledFirstItem").value(false))
                // 原文跟着回来（#119）：保存那一刻手里要还握着它
                .andExpect(jsonPath("$.data.json").value(json))
                .andExpect(jsonPath("$.data.fields[0].name").value("order_no"))
                .andExpect(jsonPath("$.data.fields[0].type").value("STRING"))
                .andExpect(jsonPath("$.data.fields[0].nullable").value(true));
    }

    /** 收不下的样例按参数问题回 400（带信封），界面直接把那句话显示在粘贴框里。 */
    @Test
    void reportsAnUnusablePastedSampleAsAParameterProblem() throws Exception {
        when(editorService.sourceFieldsFromSample(eq(3L), eq(1), anyString()))
                .thenThrow(new IllegalArgumentException("收对象或数组：粘一份 JSON"));

        mockMvc.perform(post("/api/jobs/3/field-mapping-editor/source-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":1,\"json\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(containsString("收对象或数组")));
    }

    /**
     * 「粘贴 JSON」拿**目标**字段清单那条路的 HTTP 契约（#118）：路径上不带 jobId（跟哪个任务、哪个动作
     * 都无关——HTTP / MQ 做目标时没有表可探），请求体只有样例原文，回来的是与探测同一份 fields 形状。
     */
    @Test
    void returnsTheTargetFieldListOfAPastedSample() throws Exception {
        String json = "{\"order_no\":\"PO-1\"}";
        when(editorService.targetFieldsFromSample(json)).thenReturn(
                new FieldMappingEditorView.TargetSample("paste", false,
                        List.of(new SourceField("order_no", "STRING", true, List.of("PO-1"))), json));

        mockMvc.perform(post("/api/field-mapping-editor/target-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"json":"{\\"order_no\\":\\"PO-1\\"}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.sampledFirstItem").value(false))
                .andExpect(jsonPath("$.data.json").value(json))
                .andExpect(jsonPath("$.data.fields[0].name").value("order_no"))
                .andExpect(jsonPath("$.data.fields[0].type").value("STRING"));
    }

    /**
     * 「点保存」时样例落库那条路的 HTTP 契约（#119）：请求体那三格（两边的归属 + 手里那份原文）就是
     * 服务那一侧的入参，回来 204（没有要回给界面的东西——打开时读回来那一份在上下文那个 GET 里）。
     */
    @Test
    void savesTheSamplesTheEditorHolds() throws Exception {
        mockMvc.perform(put("/api/jobs/3/field-mapping-editor/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":{"step":1,"json":"{\\"order_no\\":\\"PO-1\\"}"},
                                 "target":{"actionId":13,"json":null}}
                                """))
                .andExpect(status().isNoContent());

        verify(editorService).saveSamples(eq(3L), argThat(request ->
                request.source().step() == 1
                        && request.source().json().equals("{\"order_no\":\"PO-1\"}")
                        && request.target().actionId() == 13L
                        && request.target().json() == null));
    }
}
