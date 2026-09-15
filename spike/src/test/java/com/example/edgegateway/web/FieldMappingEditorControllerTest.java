package com.example.edgegateway.web;

import com.example.edgegateway.service.FieldMappingEditorService;
import com.example.edgegateway.service.MappingDraftValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
