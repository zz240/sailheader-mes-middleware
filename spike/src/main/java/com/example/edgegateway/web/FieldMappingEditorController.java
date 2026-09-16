package com.example.edgegateway.web;

import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.service.FieldMappingEditorService;
import com.example.edgegateway.service.MappingDraftValidationService;
import com.example.edgegateway.web.dto.FieldMappingEditorView;
import com.example.edgegateway.web.dto.MappingDraftRequest;
import com.example.edgegateway.web.dto.MappingDraftValidation;
import com.example.edgegateway.web.dto.SourceSampleRequest;
import com.example.edgegateway.web.dto.TargetSampleRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 新版字段映射编辑器的数据端点（#99 起）：编辑器打开时拿「目标字段清单从哪来」和「来源字段清单」，
 * 点「校验配置」时把还没保存的草稿交给一个只读接口判（#103）。
 *
 * <p>目标清单和来源样例都只在编辑器打开时存在，不写数据库（#98 C2）；校验草稿同样只读。</p>
 */
@RestController
@RequestMapping("/api")
public class FieldMappingEditorController {

    private final FieldMappingEditorService editorService;
    private final MappingDraftValidationService draftValidationService;

    public FieldMappingEditorController(FieldMappingEditorService editorService,
                                        MappingDraftValidationService draftValidationService) {
        this.editorService = editorService;
        this.draftValidationService = draftValidationService;
    }

    /** GET /api/jobs/{jobId}/field-mapping-editor?actionId=&actionIndex=：编辑器上下文。 */
    @GetMapping("/jobs/{jobId}/field-mapping-editor")
    public FieldMappingEditorView context(@PathVariable Long jobId,
                                          @RequestParam(required = false) Long actionId,
                                          @RequestParam(required = false) Integer actionIndex) {
        return editorService.context(jobId, actionId, actionIndex);
    }

    /** GET /api/jobs/{jobId}/field-mapping-editor/source-fields?step=：某个来源步骤的字段清单。 */
    @GetMapping("/jobs/{jobId}/field-mapping-editor/source-fields")
    public FieldMappingEditorView.SourceSample sourceFields(@PathVariable Long jobId,
                                                            @RequestParam Integer step) {
        return editorService.sourceFields(jobId, step);
    }

    /**
     * POST /api/jobs/{jobId}/field-mapping-editor/source-fields：「粘贴 JSON」那条路（#115）。
     *
     * <p>与同路径的 GET 并列，不是谁兜底谁：GET 从运行记录里取，这个用请求体里那份样例。
     * 推断是同一份实现（样例展开），所以两边推出来的字段形状一致；样例只在编辑器里存在，不落库。</p>
     */
    @PostMapping("/jobs/{jobId}/field-mapping-editor/source-fields")
    public FieldMappingEditorView.SourceSample sourceFieldsFromSample(@PathVariable Long jobId,
                                                                      @RequestBody SourceSampleRequest request) {
        return editorService.sourceFieldsFromSample(jobId, request.step(), request.json());
    }

    /** POST /api/actions/{id}/probe-target：连库探测写目标那张表的结构。 */
    @PostMapping("/actions/{id}/probe-target")
    public SourceStructure probeTarget(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        String table = request.get("table") == null ? null : request.get("table").toString();
        boolean refresh = Boolean.TRUE.equals(request.get("refresh"));
        return editorService.probeTargetFields(id, table, refresh);
    }

    /**
     * POST /api/field-mapping-editor/target-fields：「粘贴 JSON」拿目标字段清单那条路（#118）。
     *
     * <p>与连库探测（上面的 probe-target）并列，不是谁兜底谁：那个连库读列，这个用请求体里那份样例，
     * 推断是同一份。它跟哪个任务、哪个动作都无关（HTTP / MQ 做目标时本来就没有表可探），所以路径上不带
     * jobId——与 {@code POST /field-mapping-editor/validate} 同一个口径。样例只在编辑器里存在，不落库。</p>
     */
    @PostMapping("/field-mapping-editor/target-fields")
    public FieldMappingEditorView.TargetSample targetFieldsFromSample(@RequestBody TargetSampleRequest request) {
        return editorService.targetFieldsFromSample(request.json());
    }

    /**
     * POST /api/field-mapping-editor/validate：「校验配置」——校验编辑器里**还没保存**的草稿（#103）。
     *
     * <p>只读且无状态：草稿全在请求体里，不落库；也没挂 jobId——它跟哪个任务、哪次运行都无关。
     * 替代不了 {@code POST /api/jobs/{id}/validate}（那个校验已经存进数据库的配置）。</p>
     */
    @PostMapping("/field-mapping-editor/validate")
    public MappingDraftValidation validateDraft(@RequestBody MappingDraftRequest request) {
        return draftValidationService.validate(request);
    }
}