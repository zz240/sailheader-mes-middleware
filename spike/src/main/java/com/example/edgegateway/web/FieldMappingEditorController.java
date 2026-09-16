package com.example.edgegateway.web;

import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.service.FieldMappingEditorService;
import com.example.edgegateway.service.MappingDraftValidationService;
import com.example.edgegateway.service.MappingTrialService;
import com.example.edgegateway.web.dto.FieldMappingEditorView;
import com.example.edgegateway.web.dto.MappingDraftRequest;
import com.example.edgegateway.web.dto.MappingDraftValidation;
import com.example.edgegateway.web.dto.MappingSampleRequest;
import com.example.edgegateway.web.dto.MappingTrialRequest;
import com.example.edgegateway.web.dto.MappingTrialResult;
import com.example.edgegateway.web.dto.SourceSampleRequest;
import com.example.edgegateway.web.dto.TargetSampleRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 新版字段映射编辑器的数据端点（#99 起）：编辑器打开时拿「目标字段清单从哪来」和「来源字段清单」，
 * 点「校验配置」时把还没保存的草稿交给一个只读接口判（#103），点「试算」时把草稿拿到那份样例上真跑一遍
 * （#116），点「保存」时把手里粘的那份样例落库（#119）。
 *
 * <p>目标清单和来源样例都只在编辑器打开时取；存下来的只有**粘进来的原文**（#119，`mapping_sample`
 * 一张表），探测那份不落库（但会连着原文一起交给编辑器——试算要拿它跑），配置格式一个字不改。</p>
 */
@RestController
@RequestMapping("/api")
public class FieldMappingEditorController {

    private final FieldMappingEditorService editorService;
    private final MappingDraftValidationService draftValidationService;
    private final MappingTrialService trialService;

    public FieldMappingEditorController(FieldMappingEditorService editorService,
                                        MappingDraftValidationService draftValidationService,
                                        MappingTrialService trialService) {
        this.editorService = editorService;
        this.draftValidationService = draftValidationService;
        this.trialService = trialService;
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
     * 推断是同一份实现（样例展开），所以两边推出来的字段形状一致；这份样例只在编辑器里活着，
     * **点保存才落库**（#119）。</p>
     */
    @PostMapping("/jobs/{jobId}/field-mapping-editor/source-fields")
    public FieldMappingEditorView.SourceSample sourceFieldsFromSample(@PathVariable Long jobId,
                                                                      @RequestBody SourceSampleRequest request) {
        return editorService.sourceFieldsFromSample(jobId, request.step(), request.json());
    }

    /**
     * PUT /api/jobs/{jobId}/field-mapping-editor/samples：点「保存」时把手里那份样例落库（#119）。
     *
     * <p>归属与原文都由请求给：来源那一格 =（任务 + 步骤），目标那一格 =（任务 + 写目标动作 id）；带原文
     * 就是「手里是粘的、存下来」，不带原文就是「手里是探测来的、把这一格删掉」。幂等，所以重试安全——
     * 它排在动作本身写库之前（工作台那一边），配置没存成时样例也不会丢。</p>
     *
     * <p>204：没有要回给界面的东西。（打开时读回来那一份在 {@code GET field-mapping-editor} 的响应里。）</p>
     */
    @PutMapping("/jobs/{jobId}/field-mapping-editor/samples")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSamples(@PathVariable Long jobId, @RequestBody MappingSampleRequest request) {
        editorService.saveSamples(jobId, request);
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
     * jobId——与 {@code POST /field-mapping-editor/validate} 同一个口径。这份样例也只在编辑器里活着，
     * **点保存才落库**（#119；归属键是写目标动作 id，由保存那个端点带上）。</p>
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

    /**
     * POST /api/field-mapping-editor/trial：「试算」——把编辑器里那份草稿拿到一份来源样例上真跑一遍（#116）。
     *
     * <p>与上面那个校验接口**并列，不并进去**：校验是静态查、便宜、保存前必跑；试算是真执行表达式、
     * 要资源上限、点按钮才跑（{@code CONTEXT.md} 里那句「试算不是配置期校验的一部分」）。同样只读、
     * 无状态、不带 jobId——不落库、不改配置。</p>
     */
    @PostMapping("/field-mapping-editor/trial")
    public MappingTrialResult trial(@RequestBody MappingTrialRequest request) {
        return trialService.trial(request);
    }
}