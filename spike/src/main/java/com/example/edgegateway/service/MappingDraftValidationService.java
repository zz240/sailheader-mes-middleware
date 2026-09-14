package com.example.edgegateway.service;

import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.FieldMappingConfig.RuleProblem;
import com.example.edgegateway.config.FieldMappingRule;
import com.example.edgegateway.mapping.JsonataRuleCompiler;
import com.example.edgegateway.web.dto.MappingDraftRequest;
import com.example.edgegateway.web.dto.MappingDraftValidation;
import com.example.edgegateway.web.dto.MappingDraftValidation.Compiled;
import com.example.edgegateway.web.dto.MappingDraftValidation.Problem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「校验配置」：判断编辑器里**还没保存**的那份草稿能不能用，问题出在哪一行（#103）。
 *
 * <p>只读且无状态：收一份草稿和它要用到的外部事实，返回问题列表与编译产物文本，不读也不写数据库，
 * 也不改 {@code FieldMappingRule} 的格式。它替代不了 {@code POST /api/jobs/{id}/validate}——那个按 job id
 * 校验已经存进数据库的配置，而编辑器里的还没保存。</p>
 *
 * <p>判断对错全部在这里，前端一条校验规则都不复制（#98 D1）。三条现成的实现直接复用，不各写一遍：</p>
 * <ul>
 *   <li>结构：{@link FieldMappingConfig#problems()}（{@code validate()} 抛的就是它的头一条）；</li>
 *   <li>链：{@link ChainConsistencyValidator#checkRules}（sourceStep 得指向链上更早的动作）；</li>
 *   <li>编译产物：{@link JsonataRuleCompiler#compile}，编译期才发现的问题（重复目标、前缀冲突）也由它报。</li>
 * </ul>
 *
 * <p>基于目标字段清单和来源样例的那几条检查（必填配没配、来源路径在不在样例里、枚举表覆没覆盖）不在这里，
 * 是下一张票（#104）的事——请求里已经带着清单和样例，形状先按 #103 定下来。</p>
 */
@Service
public class MappingDraftValidationService {

    /**
     * 规则 JSON 的排版：缩进 2 空格、换行固定 `\n`。
     *
     * <p>默认那个排版器用的是平台换行符，同一份配置在 Windows 和 Linux 上编出来的文本不一样——
     * 这段文本要原样显示给用户看、还要在测试里断言，所以固定成 `\n`。</p>
     */
    private static final PrettyPrinter PRINTER =
            new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));

    private final ObjectMapper objectMapper;

    public MappingDraftValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 校验一份草稿：错误、警告、编译产物。 */
    public MappingDraftValidation validate(MappingDraftRequest request) {
        List<Problem> errors = new ArrayList<>();
        List<Problem> warnings = new ArrayList<>();

        FieldMappingConfig config;
        try {
            config = FieldMappingConfig.from(request.draft() == null ? Map.of() : request.draft());
        } catch (RuntimeException e) {
            // 草稿连配置都读不出来（未知字段、认不出的转换名…）：报一条，定位不到行；
            // 规则 JSON 那一页照样把收到的这份草稿显示出来，好让人看清后端读到的是什么
            errors.add(new Problem(null, null, "这份草稿没法读成字段映射配置：" + e.getMessage()));
            return new MappingDraftValidation(false, errors, warnings,
                    new Compiled(null, prettyJson(request.draft())));
        }

        // 两处来的问题用同一个形状装（都定位到某条规则）：结构来自 FieldMappingConfig，
        // sourceStep 链一致性来自 ChainConsistencyValidator——各自都是现成的实现，这里只往外交接
        List<RuleProblem> problems = new ArrayList<>(config.problems());
        problems.addAll(chainProblems(config, request));
        for (RuleProblem problem : problems) {
            errors.add(new Problem(problem.target(), problem.parent(), problem.message()));
        }
        warnings.addAll(missingSourceStepHints(config, request));

        Compiled compiled = compile(config, errors);
        return new MappingDraftValidation(errors.isEmpty(), errors, warnings, compiled);
    }

    /** sourceStep 链一致性：复用链级校验那条规则（只查顶层规则，组内子规则继承组节点）。 */
    private List<RuleProblem> chainProblems(FieldMappingConfig config, MappingDraftRequest request) {
        if (!isRules(config)) {
            return List.of(); // 手写表达式没有规则树可查
        }
        Set<Integer> earlier = new HashSet<>(
                request.earlierActionIndexes() == null ? List.of() : request.earlierActionIndexes());
        List<RuleProblem> problems = new ArrayList<>();
        for (ChainConsistencyValidator.SourceStepProblem problem
                : ChainConsistencyValidator.checkRules(config.rules(), request.actionIndex(), earlier)) {
            // 链级校验只查顶层规则，所以没有父行
            problems.add(new RuleProblem(problem.rule().target(), null, problem.message()));
        }
        return problems;
    }

    /**
     * 顶层规则漏写 sourceStep 的提示（不是错误）。
     *
     * <p>{@code sourceStep = null} 在后端合法——被当成「任务输入」（0），所以后端查不出这是个错误，
     * 只能提示（#98 D10）。编辑器产出的规则都显式写着 sourceStep，这条是给手写/存量草稿兜底的。</p>
     */
    private List<Problem> missingSourceStepHints(FieldMappingConfig config, MappingDraftRequest request) {
        if (!isRules(config)) {
            return List.of();
        }
        List<Problem> hints = new ArrayList<>();
        for (FieldMappingRule rule : config.rules()) {
            if (rule.sourceStep() != null) {
                continue;
            }
            hints.add(new Problem(rule.target(), null, missingSourceStepMessage(request.defaultSourceStep())));
        }
        return hints;
    }

    private String missingSourceStepMessage(Integer defaultSourceStep) {
        String tail = defaultSourceStep != null && defaultSourceStep > 0
                ? "；编辑器顶部选的默认来源是动作 " + defaultSourceStep
                : "";
        return "顶层规则没写 sourceStep——后端会把「没写」当成任务输入（编号 0），不会报错" + tail;
    }

    /** 编译产物：编译出的 JSONata 与后端按现在的规则读出来的那份 JSON。 */
    private Compiled compile(FieldMappingConfig config, List<Problem> errors) {
        String rulesJson = rulesJson(config);
        if (isRules(config)) {
            if (config.rules() == null || config.rules().isEmpty()) {
                // 没规则可编译（「至少一条映射规则」上面已经报过了，这里不再重复报一条）
                return new Compiled(null, rulesJson);
            }
            try {
                return new Compiled(JsonataRuleCompiler.compile(config.rules()), rulesJson);
            } catch (JsonataRuleCompiler.CompileException e) {
                errors.add(new Problem(e.target(), e.parent(), e.getMessage()));
            } catch (IllegalArgumentException e) {
                errors.add(new Problem(null, null, e.getMessage()));
            }
            return new Compiled(null, rulesJson);
        }
        String expression = config.expression();
        return new Compiled(expression == null || expression.isBlank() ? null : expression, rulesJson);
    }

    /** 规则 JSON：后端按现在的规则读出来的那一份。 */
    private String rulesJson(FieldMappingConfig config) {
        return prettyJson(config.toMap());
    }

    /** 排版成给用户看的那份文本（序列化不出来时给 null，界面上那个标签页就没内容）。 */
    private String prettyJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writer(PRINTER).writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private boolean isRules(FieldMappingConfig config) {
        return !"EXPRESSION".equals(config.mode());
    }
}
