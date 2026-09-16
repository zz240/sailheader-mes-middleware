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
 * <p>判断对错全部在这里，前端一条校验规则都不复制（#98 D1）。几条现成的实现直接复用，不各写一遍：</p>
 * <ul>
 *   <li>结构：{@link FieldMappingConfig#problems()}（{@code validate()} 抛的就是它，里面也含编译期才发现的
 *       目标字段重复 / 前缀冲突 / 目标字段路径不合法，见 #113、#114）；</li>
 *   <li>链：{@link ChainConsistencyValidator#checkRules}（sourceStep 得指向链上更早的动作）；</li>
 *   <li>编译产物：{@link JsonataRuleCompiler#compile}，草稿编不出来时补一条错误；</li>
 *   <li>依赖**目标字段清单与来源样例**的那几条：{@link MappingSampleChecks}，全是警告（#104 起，
 *       #113 把「必填没配来源」「来源字段不在样例里」两条从错误降下来）。</li>
 * </ul>
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

        // 错误只有两类（#113 / ADR-0012 决策 4），分法是「后端会不会拒」：结构错（FieldMappingConfig，
        // 其中编译期才发现的目标字段重复/前缀冲突现在也由它报）与链问题（ChainConsistencyValidator）
        // ——各自的实现都是现成的，这里只往外交接
        List<RuleProblem> problems = new ArrayList<>(config.problems());
        problems.addAll(chainProblems(config, request));
        for (RuleProblem problem : problems) {
            addOnce(errors, new Problem(problem.target(), problem.parent(), problem.message()));
        }
        // 警告是「只在来源字段样例上成立」的那几条 + 两条软提示（顶层漏写 sourceStep、日期格式串没试过）：
        // 后端从来不拒它们，编辑器也不该替它拦——样例只是最近一次成功运行的取样，陈旧时会误伤
        warnings.addAll(missingSourceStepHints(config, request));
        for (RuleProblem warning : sampleWarnings(config, request)) {
            addOnce(warnings, new Problem(warning.target(), warning.parent(), warning.message()));
        }

        Compiled compiled = compile(config, errors);
        return new MappingDraftValidation(errors.isEmpty(), errors, warnings, compiled);
    }

    /**
     * 同一条问题只留一条。
     *
     * <p>结构校验与编译器会看到同一件事：结构那一边按片段报一条，编译器在同一处也编不出来、报了逐字相同
     * 的一条（#112 起片段问题带序号，两条一模一样）。界面上把一条错显示成两条，会让人以为有两个问题要改。</p>
     */
    private static void addOnce(List<Problem> problems, Problem problem) {
        Problem same = new Problem(problem.target(), problem.parent(), problem.message());
        if (!problems.contains(same)) {
            problems.add(same);
        }
    }

    /**
     * 要靠**目标字段清单和来源样例**才判得出来的**警告**（#104 起，#111 补上日期格式串那条，
     * #113 把「必填没配来源」「来源字段不在样例里」两条从错误降到这里）。
     *
     * <p>定成警告而不是错误，是因为它们顺着样例与清单走：样例是最近一次成功运行的取样，陈旧的时候会
     * 误伤，而这两件事后端从来不拒（真正拦保存的还是结构错与链问题，见 {@link #validate}）。特别是
     * 「必填没配来源」——它说的是目标清单的事实（这个必填字段现在是空的），不是「这一份配置存不进去」：
     * 清除掉一个必填字段的行之后保存不再被它拦下，但那一行仍然该提醒（#113 验收四、五）。</p>
     *
     * <p>手写表达式（mode=EXPRESSION）没有规则树，这几条查不了；清单和样例空着时
     * {@link MappingSampleChecks} 自己一条都不报。</p>
     */
    private List<RuleProblem> sampleWarnings(FieldMappingConfig config, MappingDraftRequest request) {
        if (!isRules(config)) {
            return List.of();
        }
        List<RuleProblem> warnings = new ArrayList<>(MappingSampleChecks.missingRequiredTargets(config, request));
        warnings.addAll(MappingSampleChecks.unknownSources(config, request));
        warnings.addAll(MappingSampleChecks.uncoveredEnumValues(config, request));
        warnings.addAll(MappingSampleChecks.textIntoNumberTarget(config, request));
        warnings.addAll(MappingSampleChecks.emptyConcatParts(config));
        warnings.addAll(MappingSampleChecks.datePatternWarnings(config, request));
        return warnings;
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
                addOnce(errors, new Problem(e.target(), e.parent(), e.getMessage()));
            } catch (IllegalArgumentException e) {
                addOnce(errors, new Problem(null, null, e.getMessage()));
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
