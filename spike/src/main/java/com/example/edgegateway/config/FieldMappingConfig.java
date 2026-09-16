package com.example.edgegateway.config;

import com.example.edgegateway.mapping.DatePatterns;
import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.model.ActionKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段映射动作配置（FIELD_MAP）。
 *
 * <p>两种模式：默认 RULES（结构化规则树，编译为 JSONata 执行）；EXPRESSION（手写 JSONata
 * 表达式逃生通道，配置了 expression 时跳过规则树）。</p>
 */
public record FieldMappingConfig(
        String mode,
        String expression,
        List<FieldMappingRule> rules) implements ActionConfig {

    public static FieldMappingConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        map.keySet().stream()
                .filter(k -> !Set.of("rules", "mode", "expression").contains(k))
                .findFirst()
                .ifPresent(k -> {
                    throw new IllegalArgumentException("未知字段: " + k);
                });
        List<FieldMappingRule> rules = new ArrayList<>();
        Object raw = map.get("rules");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> ruleMap) {
                    rules.add(FieldMappingRule.from(ConfigValues.map(ruleMap)));
                }
            }
        }
        return new FieldMappingConfig(
                ConfigValues.str(map.get("mode")),
                ConfigValues.str(map.get("expression")),
                rules);
    }

    @Override
    public ActionKind type() {
        return ActionKind.FIELD_MAP;
    }

    /**
     * 一条结构问题：出错的那条规则（{@code target} 定位，{@code parent} 是它所属数组行的目标字段）+ 说明。
     *
     * <p>{@code target} 和 {@code parent} 都可能为空——整份配置级别的问题（没有规则、模式配错）定位不到某一行。</p>
     */
    public record RuleProblem(String target, String parent, String message) {
    }

    /**
     * 保存这道门：结构错（{@link #problems()}）**全部**报出来，用 `；` 连接（#113 / ADR-0012 决策 4）。
     *
     * <p>以前只抛头一条：用户改一条、点一次保存、再来一条，来回好几轮。连接符与链检查
     * （{@code ChainConsistencyValidator.describeAll}）的口径一致——那一条也是多条问题拼成一条消息。</p>
     */
    @Override
    public void validate() {
        List<RuleProblem> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", problems.stream()
                    .map(RuleProblem::message)
                    .toList()));
        }
    }

    /**
     * 结构校验的**全部**问题（按规则树顺序，一条规则可以有多条）。
     *
     * <p>{@link #validate()} 抛的就是这里拼起来的那一条；「校验配置」那个只读接口（#103）要一次把问题都
     * 列出来，所以两边共用这一份实现，不各写一遍。</p>
     *
     * <p>除了逐条规则自己的结构错，这里还报**编译期才发现的冲突**（重复目标字段、前缀冲突，见
     * {@link #targetConflicts}）——它们以前只在启用重建路由时炸，任务停用状态下能存进去，
     * 门和事实不一致（#113 / ADR-0012 决策 4）。</p>
     */
    public List<RuleProblem> problems() {
        List<RuleProblem> problems = new ArrayList<>();
        if ("EXPRESSION".equals(mode)) {
            if (expression == null || expression.isBlank()) {
                problems.add(new RuleProblem(null, null, "mode=EXPRESSION 需要 expression 表达式"));
            }
            return problems; // 手写表达式模式不校验规则树
        }
        if (expression != null) {
            problems.add(new RuleProblem(null, null, "只有 mode=EXPRESSION 才允许 expression"));
        }
        if (rules == null || rules.isEmpty()) {
            problems.add(new RuleProblem(null, null, "字段映射动作需要至少一条映射规则"));
            return problems;
        }
        collectProblems(rules, 0, null, problems);
        for (RuleProblem conflict : targetConflicts(rules, null)) {
            problems.add(conflict);
        }
        return problems;
    }

    /**
     * 编译期才发现的冲突：目标字段重复、目标字段前缀冲突。
     *
     * <p>走法与 {@code JsonataRuleCompiler.insert} 一致（同一种目标树、同一套判据、同一种消息），因为
     * 这里报的必须是**编译器真会拒的那几条**：报多了会让合法的配置存不进去，报少了门和事实又不一致。
     * 整条规则表一次走完，所以一次能报出全部冲突（编译器是撞上第一条就抛）。</p>
     *
     * <p><b>两处会走散</b>：这里是编译器那段的第二份实现（写在一起的话，编译器要反过来依赖 config 包）。
     * 约束它们的是 {@code JsonataRuleCompilerTest.savingGateAndCompilerReportTheSameConflicts}——同一批
     * 冲突两边报出来的话必须逐字相同；动了这里或那里，那条用例会红。</p>
     *
     * <p>拼接片段上的问题不在这里：{@link #concatPartProblems} 已经逐条报过了，两边共用同一份判据，
     * 服务那边还会把逐字相同的两条合成一条（{@code MappingDraftValidationService.addOnce}）。</p>
     *
     * <p>只对**结构本身没毛病**的规则跑：目标字段空着、转换认不出这些上面的 {@link #collectProblems}
     * 已经各报一条了，再补一条说的同一件事只会是噪声（编译器也不会走到那一步）。</p>
     */
    private List<RuleProblem> targetConflicts(List<FieldMappingRule> rules, String parentTarget) {
        List<RuleProblem> conflicts = new ArrayList<>();
        TargetTree tree = new TargetTree();
        for (FieldMappingRule rule : rules) {
            insertTarget(tree, rule, parentTarget, conflicts);
        }
        return conflicts;
    }

    /** 往目标树里插一条规则，顺路报出它撞上的那些冲突（与编译器的走法一致，只是不抛、接着走）。 */
    private void insertTarget(TargetTree node, FieldMappingRule rule, String parentTarget,
                              List<RuleProblem> problems) {
        String target = rule.target();
        if (target == null || target.isBlank()) {
            return;
        }
        String[] segments = target.split("\\.");
        for (String segment : segments) {
            if (segment.isBlank()) {
                // 空的一段（`a..b`）编译器也编不出来——与「目标字段空着」一样，那是 collectProblems 报的事
                return;
            }
        }
        TargetTree current = node;
        for (int i = 0; i < segments.length; i++) {
            TargetTree next = current.children.get(segments[i]);
            if (i < segments.length - 1 && next != null && next.leaf && !next.group) {
                // 中间节点已经有非组叶子：先映射 "a.b" 再映射 "a.b.c" 时 c 会被静默丢弃
                problems.add(conflict(rule, parentTarget, "目标字段前缀冲突"));
                return;
            }
            if (next == null) {
                next = new TargetTree();
                current.children.put(segments[i], next);
            }
            current = next;
        }
        boolean group = rule.children() != null && !rule.children().isEmpty();
        if (current.leaf) {
            problems.add(conflict(rule, parentTarget, "目标字段重复映射"));
            return;
        }
        if (!current.children.isEmpty() && !group) {
            problems.add(conflict(rule, parentTarget, "目标字段前缀冲突"));
            return;
        }
        current.leaf = true;
        current.group = group;
        if (group) {
            for (FieldMappingRule child : rule.children()) {
                insertTarget(current, child, target, problems);
            }
        }
    }

    /** 一条冲突：消息形状与 {@code JsonataRuleCompiler} 那几条逐字一致（服务那边据此合成一条）。 */
    private static RuleProblem conflict(FieldMappingRule rule, String parentTarget, String message) {
        return new RuleProblem(rule.target(), parentTarget, message + ": " + rule.target());
    }

    /** 目标树的一个节点（只看冲突，不看叶子怎么编译）。 */
    private static final class TargetTree {
        private final Map<String, TargetTree> children = new LinkedHashMap<>();
        private boolean leaf;
        private boolean group;
    }

    /**
     * 递归收集规则树的问题。
     *
     * @param depth        嵌套组层级（INDEX 只在组内合法，0 = 顶层）
     * @param parentTarget 这一层规则所属组节点的目标字段（顶层为 null）
     */
    private void collectProblems(List<FieldMappingRule> list, int depth, String parentTarget,
                                 List<RuleProblem> problems) {
        for (FieldMappingRule rule : list) {
            if (rule.target() == null || rule.target().isBlank()) {
                problems.add(new RuleProblem(rule.target(), parentTarget, "映射规则缺少目标字段 target"));
                continue;
            }
            boolean group = rule.children() != null && !rule.children().isEmpty();
            if (group) {
                if (rule.iterate() != null && !"FOR_EACH".equals(rule.iterate())) {
                    problems.add(problem(rule, parentTarget, "组节点 iterate 仅支持 FOR_EACH: " + rule.target()));
                }
                if (rule.source() == null || rule.source().isBlank()) {
                    problems.add(problem(rule, parentTarget, "组节点缺少来源数组 source: " + rule.target()));
                }
                for (FieldMappingRule child : rule.children()) {
                    if (child.sourceStep() != null) {
                        problems.add(new RuleProblem(child.target(), rule.target(),
                                "组内子规则不允许再选 sourceStep（继承组节点）: "
                                        + rule.target() + " -> " + child.target()));
                    }
                }
                collectProblems(rule.children(), depth + 1, rule.target(), problems);
                continue;
            }
            if (rule.transform() == TransformType.INDEX) {
                if (depth == 0) {
                    problems.add(problem(rule, parentTarget, "INDEX 转换只能在数组组内使用: " + rule.target()));
                } else if (!"$index".equals(rule.source())) {
                    problems.add(problem(rule, parentTarget, "INDEX 转换的 source 必须为 $index: " + rule.target()));
                }
                continue;
            }
            if (rule.transform() == TransformType.CONCAT) {
                problems.addAll(concatPartProblems(rule, depth, parentTarget));
                continue;
            }
            if (rule.transform() == TransformType.CONSTANT
                    && (rule.constant() == null || rule.constant().isBlank())) {
                problems.add(problem(rule, parentTarget, "常量转换需要 constant"));
            }
            if (rule.transform() == TransformType.ENUM && rule.enumMap().isEmpty()) {
                problems.add(problem(rule, parentTarget, "枚举转换需要 enumMap"));
            }
            if (rule.transform() == TransformType.DATE) {
                // 非法格式串在运行时必炸（JsonataFunctions.formatDate 的 ofPattern 在所有 try 之外），
                // 所以它是配置期错误：保存与「校验配置」两边都靠这一条拦（#111 / ADR-0012 决策 2）
                String patternProblem = DatePatterns.problem(rule.datePattern());
                if (patternProblem != null) {
                    problems.add(problem(rule, parentTarget, patternProblem));
                }
            }
            if (rule.transform() != TransformType.CONSTANT
                    && (rule.source() == null || rule.source().isBlank())) {
                problems.add(problem(rule, parentTarget, "映射规则缺少来源字段 source"));
            }
        }
    }

    private RuleProblem problem(FieldMappingRule rule, String parentTarget, String message) {
        return new RuleProblem(rule.target(), parentTarget, message);
    }

    /**
     * 拼接片段的检查（#112）：片段是字符串常量，或一个带转换的对象。
     *
     * <p>四种形状：字符串常量、取值片段 `{"path": "..."}`（老形状，不带 transform 就是「直接取值」的
     * 退化情形）、加工片段 `{"path": "...", "transform": "DATE", "datePattern": "..."}`、行号片段
     * `{"transform": "INDEX", "startAt": 1}`（源就是行号本身，不带 path）。</p>
     *
     * <p>问题都带上「第几个片段」：一个片段配错了，光有目标字段认不出是哪一个（#112 验收五）。</p>
     */
    private List<RuleProblem> concatPartProblems(FieldMappingRule rule, int depth, String parentTarget) {
        if (rule.parts() == null || rule.parts().isEmpty()) {
            return List.of(problem(rule, parentTarget, "CONCAT 转换需要 parts 片段: " + rule.target()));
        }
        List<RuleProblem> problems = new ArrayList<>();
        for (int i = 0; i < rule.parts().size(); i++) {
            problems.addAll(partProblems(rule, rule.parts().get(i), i + 1, depth, parentTarget));
        }
        return problems;
    }

    /** 一个片段的问题（可能不止一条：路径没写、参数也没填）。 */
    private List<RuleProblem> partProblems(FieldMappingRule rule, Object part, int index, int depth,
                                           String parentTarget) {
        if (part instanceof String) {
            return List.of(); // 字符串常量：老形状，没有参数可言
        }
        if (!(part instanceof Map<?, ?> map)) {
            return List.of(partProblem(rule, parentTarget, index, "片段只能是字符串常量或一个对象"));
        }
        String transform = ConfigValues.str(map.get("transform"));
        transform = transform == null ? "" : transform.trim();
        List<RuleProblem> problems = new ArrayList<>();
        boolean indexPart = TransformType.INDEX.name().equals(transform);
        // 行号片段的源就是行号本身，别的片段都要写 path（老形状与加工片段一样）
        if (!indexPart && (ConfigValues.str(map.get("path")) == null || ConfigValues.str(map.get("path")).isBlank())) {
            problems.add(partProblem(rule, parentTarget, index, "缺少来源字段 path"));
        }
        if (indexPart) {
            if (depth == 0) {
                // 与行级 INDEX 同一条理由：$index 只在数组元素里有意义
                problems.add(partProblem(rule, parentTarget, index, "行号片段只能在数组映射的子行里用"));
            }
        } else if (transform.isEmpty() || TransformType.STRING.name().equals(transform)
                || TransformType.INT.name().equals(transform)) {
            // 直接取值与数字转换没有别的参数
        } else if (TransformType.DATE.name().equals(transform)) {
            String patternProblem = DatePatterns.problem(ConfigValues.str(map.get("datePattern")));
            if (patternProblem != null) {
                problems.add(partProblem(rule, parentTarget, index, patternProblem));
            }
        } else if (TransformType.ENUM.name().equals(transform)) {
            if (ConfigValues.map(map.get("enumMap")).isEmpty()) {
                problems.add(partProblem(rule, parentTarget, index, "枚举转换需要 enumMap"));
            }
        } else {
            problems.add(partProblem(rule, parentTarget, index, "认不出的片段转换 " + transform));
        }
        return problems;
    }

    /** 一条片段问题：消息带上「第几个片段」，末尾照旧带上目标字段（与这个文件里别的消息同一条口径）。 */
    private RuleProblem partProblem(FieldMappingRule rule, String parentTarget, int index, String message) {
        return new RuleProblem(rule.target(), parentTarget,
                "拼接第 " + index + " 个片段：" + message + ": " + rule.target());
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (mode != null) {
            map.put("mode", mode);
        }
        if (expression != null) {
            map.put("expression", expression);
        }
        map.put("rules", rules.stream().map(this::ruleMap).toList());
        return map;
    }

    private Map<String, Object> ruleMap(FieldMappingRule r) {
        Map<String, Object> rule = new LinkedHashMap<>();
        if (r.sourceStep() != null) {
            rule.put("sourceStep", r.sourceStep());
        }
        if (r.source() != null) {
            rule.put("source", r.source());
        }
        rule.put("target", r.target());
        rule.put("transform", r.transform().name());
        if (r.constant() != null) {
            rule.put("constant", r.constant());
        }
        if (r.enumMap() != null && !r.enumMap().isEmpty()) {
            rule.put("enumMap", r.enumMap());
        }
        if (r.datePattern() != null) {
            rule.put("datePattern", r.datePattern());
        }
        if (r.iterate() != null) {
            rule.put("iterate", r.iterate());
        }
        if (r.children() != null && !r.children().isEmpty()) {
            rule.put("children", r.children().stream().map(this::ruleMap).toList());
        }
        if (r.parts() != null && !r.parts().isEmpty()) {
            rule.put("parts", r.parts());
        }
        if (r.startAt() != null) {
            rule.put("startAt", r.startAt());
        }
        return rule;
    }
}
