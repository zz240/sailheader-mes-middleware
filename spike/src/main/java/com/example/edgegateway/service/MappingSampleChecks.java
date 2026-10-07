package com.example.edgegateway.service;

import com.example.edgegateway.config.action.FieldMappingConfig;
import com.example.edgegateway.config.action.FieldMappingConfig.RuleProblem;
import com.example.edgegateway.config.action.FieldMappingRule;
import com.example.edgegateway.mapping.DatePatterns;
import com.example.edgegateway.mapping.JsonataFunctions;
import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.web.dto.MappingDraftRequest;
import com.example.edgegateway.web.dto.MappingDraftRequest.TargetFieldSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 依赖**目标字段清单和来源样例**的那几条草稿检查（#104 起，#111 补上日期格式串那条），外加一条只看
 * 草稿的拼接检查。
 *
 * <p>{@link MappingDraftValidationService} 原来那几条只看草稿本身（结构、sourceStep 链、编译产物）；
 * 这几条要看草稿之外的两份事实，所以后端本来查不出来。它们照亮的正是 ADR-0011 背景里那类「不报错但
 * 结果不对」的情况（#98 问题陈述）：来源路径不存在时目标字段整个消失、枚举查不到键时这个字段从结果里
 * 整个消失、必填字段没配来源时整个消失、文本直接写进数字字段、日期格式串在样例值上解析不出来时原样
 * 透传。另外两条（拼接里有空片段、顶层规则漏写 sourceStep）本身不看样例，只是同样是「静默出错」那一类
 * ——sourceStep 那条在 {@link MappingDraftValidationService} 里出。</p>
 *
 * <p><b>只查样例里的值，不执行表达式</b>（#98 D9）：来源字段在不在、枚举覆没覆盖、日期格式串跑不跑得
 * 出来，都是拿这份样例里的实际取值（结构探测给的 samples）比对的，不试算任何一条表达式，也就不需要
 * 试算那套机制。</p>
 *
 * <p>定级（#104 验收，#113 改）：这里出去的问题**全是警告**——它们要么顺着样例数据与用户手输的片段走
 * （样例陈旧、片段是故意留的时候会误伤），要么说的是目标清单的事实（必填字段现在是空的），而后端从来不
 * 拒这几种。真正拦保存的只有结构错与链问题，那两条在 {@link MappingDraftValidationService} 里出。</p>
 *
 * <p>要看清单或样例的那几条，在那份输入空着时一条都不报：探不出结构不是配置的错。特别是「来源字段不在
 * 样例里」——取样例时挂了就整份字段清单都不在，那时候报出来的是探测的故障，不是草稿的问题。
 * {@link #emptyConcatParts} 两样都不要，所以不受这条限制。</p>
 *
 * <p><b>一处例外</b>：{@link #datePatternWarnings} 在**试不了**时反而要说话——「没在样例上试过」正是
 * 手上没有样例时该说的话（#111 验收四：只提示、不拦保存）。它进的是响应里的 {@code warnings}，因为
 * 那个响应只有错误 / 警告两档（与顶层漏写 sourceStep 那条提示同一档，见
 * {@link MappingDraftValidationService}）。</p>
 *
 * <p>路径口径：样例给的是探测路径（{@code items[0].materialCode}），草稿里的数组子行写的是相对路径
 * （{@code materialCode}），目标清单给的是编辑器路径（数组元素不带下标）。这里把样例路径折成编辑器口径
 * （去掉下标）再比——比对之前两边得说同一种话。</p>
 */
final class MappingSampleChecks {

    private MappingSampleChecks() {
    }

    /**
     * 草稿里配了、但样例里没有的来源字段。
     *
     * <p>数组子行的来源写的是**相对路径**，按沿路的组节点逐层补回前缀再查（嵌套数组因此能一路补成
     * `items.parts.code`）；{@code $root} 前缀是组内引用外层表头字段，去掉它按绝对路径查；行号
     * {@code $index} 不是来源路径，不查。</p>
     *
     * <p>只判**来源步骤跟这份样例一样**的规则：请求里只带一份样例，别的步骤的字段根本不在这一份清单里，
     * 拿它去判必然报「来源字段不在样例里」——跨步骤引用、以及顶部默认来源一换之后留在原步骤的那些行，
     * 会整片误报。{@code sourceFieldsStep} 没给时这一条不判。</p>
     */
    static List<RuleProblem> unknownSources(FieldMappingConfig config, MappingDraftRequest request) {
        Set<String> sample = sampleNames(request);
        if (sample.isEmpty() || request.sourceFieldsStep() == null) {
            return List.of();
        }
        List<RuleProblem> problems = new ArrayList<>();
        for (FieldMappingRule rule : rules(config)) {
            collectUnknownSources(rule, RuleScope.root(rule, request), sample, problems);
        }
        return problems;
    }

    /**
     * 必填的目标字段没有配置来源（拿请求里的目标清单判断）。
     *
     * <p>数组字段整行报一条：没有数组映射那一行时，它的元素字段一条也进不来，逐个报只会刷屏；
     * 数组配了、只是某个必填子字段没配，才逐条报子字段。</p>
     *
     * <p>它说的是**目标清单的事实**（这个必填字段现在是空的），不是「这一行被校验了」：清除掉一个必填
     * 字段的行之后，那一行不进草稿，清单里的必填字段照样没着落，这条照旧报得出来——而保存不再被它拦住
     * （#113 验收四、五）。</p>
     */
    static List<RuleProblem> missingRequiredTargets(FieldMappingConfig config, MappingDraftRequest request) {
        List<TargetFieldSpec> targets = targets(request);
        if (targets.isEmpty()) {
            return List.of();
        }
        List<RuleProblem> problems = new ArrayList<>();
        for (TargetFieldSpec target : targets) {
            collectMissingRequired(target, "", null, config.rules(), problems);
        }
        return problems;
    }

    /** 枚举表没有覆盖样例里出现过的值——查不到键时这个字段从结果里整个消失，不报错，只能在这里提醒。 */
    static List<RuleProblem> uncoveredEnumValues(FieldMappingConfig config, MappingDraftRequest request) {
        Map<String, SourceField> sample = sampleFields(request);
        if (sample.isEmpty() || request.sourceFieldsStep() == null) {
            return List.of();
        }
        List<RuleProblem> problems = new ArrayList<>();
        for (FieldMappingRule rule : rules(config)) {
            collectUncoveredEnumValues(rule, RuleScope.root(rule, request), sample, problems);
        }
        return problems;
    }

    /** 文本值直接写进数字类型的字段——不套数字转换的话，运行到那一条会失败。 */
    static List<RuleProblem> textIntoNumberTarget(FieldMappingConfig config, MappingDraftRequest request) {
        Map<String, String> types = targetTypes(targets(request));
        Map<String, SourceField> sample = sampleFields(request);
        if (types.isEmpty() || sample.isEmpty() || request.sourceFieldsStep() == null) {
            return List.of();
        }
        List<RuleProblem> problems = new ArrayList<>();
        for (FieldMappingRule rule : rules(config)) {
            collectTextIntoNumber(rule, RuleScope.root(rule, request), types, sample, problems);
        }
        return problems;
    }

    /**
     * 拼接里的空片段——不会报错，但通常是无意留下的（这一行最后会拼出空字符串，看着像配了）。
     *
     * <p>只看**编译器和结构校验都不管**的那一种：常量片段是空串，或 {@code {"path": ""}}（键在、值是空串）。
     * 片段整个不是字符串也不是 {@code {"path": ...}}、或 {@code path} 键整个没写，{@link
     * com.example.edgegateway.config.action.FieldMappingConfig#problems()} 已经各报一条错误了，这里不再补一条
     * 说的同一件事的警告。</p>
     */
    static List<RuleProblem> emptyConcatParts(FieldMappingConfig config) {
        List<RuleProblem> problems = new ArrayList<>();
        for (FieldMappingRule rule : rules(config)) {
            collectEmptyConcatParts(rule, null, problems);
        }
        return problems;
    }

    /**
     * 日期格式串在**样例值**上真跑一遍（#111 验收三、四）。
     *
     * <p>不执行表达式，只拿这一行来源字段在这份样例里的实际取值，过一遍 runtime 真正用的那个格式化实现
     * （{@link JsonataFunctions#formatDate}）：跑不出来时运行时会把这个值**原样发出去**、任务照样成功，
     * 错只在目标那边看得见——所以在这里说出来。定成**警告**：样例是最近一次成功运行的取样，会陈旧，
     * 而且是「值和格式对不对得上」，后端从来不拦这种。</p>
     *
     * <p>试不了的时候（没带样例、样例是别的步骤的、这一行的来源字段没探到取值）只给一句
     * 「没在样例上试过」的提示：冷启动手上没有样例是常态，不该因此配不完（#111 验收四）。</p>
     *
     * <p>格式串本身非法是结构校验那条**错误**的事（{@code FieldMappingConfig.problems()}），这里既不重复
     * 报，也不拿一个非法格式串去试——平台解析器会直接抛。</p>
     */
    static List<RuleProblem> datePatternWarnings(FieldMappingConfig config, MappingDraftRequest request) {
        Map<String, SourceField> sample = sampleFields(request);
        List<RuleProblem> problems = new ArrayList<>();
        for (FieldMappingRule rule : rules(config)) {
            collectDatePatternWarnings(rule, RuleScope.root(rule, request), sample, problems);
        }
        return problems;
    }

    // ---------- 遍历：每条规则一次，带上它在规则树里的位置 ----------

    /**
     * 一条规则在规则树里的位置。
     *
     * <p>子规则的来源写的是相对路径、目标写的也是相对路径，所以两样都要沿路累积：{@code items} →
     * {@code items.parts}（来源前缀）、{@code bom.bomLines} → {@code bom.bomLines.lines}（目标前缀）。
     * {@code step} 是这条规则的来源步骤（子规则继承组节点，自己不带），用来认「这份样例是不是它那一步的」；
     * {@code parent} 只管问题往界面上哪一行挂。</p>
     *
     * @param parent       这条规则所属的组节点（顶层为 null）
     * @param sourcePrefix 沿路累积的来源数组路径（编辑器口径，不带下标）；顶层是空串
     * @param targetPrefix 沿路累积的目标路径；顶层是空串
     * @param step         这条规则的来源步骤；null 表示没写（后端当成任务输入）又没给默认来源
     */
    private record RuleScope(MappingDraftRequest request, FieldMappingRule parent, String sourcePrefix,
                             String targetPrefix, Integer step) {

        /** 顶层规则：位置由它自己定，来源前缀是空的。 */
        static RuleScope root(FieldMappingRule rule, MappingDraftRequest request) {
            return new RuleScope(request, null, "", "",
                    rule.sourceStep() != null ? rule.sourceStep() : request.defaultSourceStep());
        }

        /** 往下走一层：来源前缀接上这一组的来源数组，目标前缀接上这一组的目标字段。 */
        RuleScope child(FieldMappingRule group) {
            return new RuleScope(request, group, arrayPathOf(sourcePrefix, sourceOf(group)),
                    targetPath(group.target()), group.sourceStep() != null ? group.sourceStep() : step);
        }

        /** 这条规则取的那份样例是不是请求里带来的那一份（步骤不明时判不了，不算数）。 */
        boolean judgedAgainstRequestSample() {
            return step != null && step.equals(request.sourceFieldsStep());
        }

        /** 来源路径：普通路径在组内是相对路径，接上前缀；`$root` 是绝对路径，只去前缀。 */
        String sourcePath(String source) {
            if (source.startsWith("$root.")) {
                return normalizePath(source.substring("$root.".length()));
            }
            return normalizePath(arrayPathOf(sourcePrefix, source));
        }

        /** 目标路径：子规则的目标是相对路径，接上前缀。 */
        String targetPath(String target) {
            String self = target == null ? "" : target;
            return targetPrefix.isEmpty() || self.isEmpty() ? targetPrefix + self : targetPrefix + "." + self;
        }
    }

    /** 前缀接上一段路径（`items` + `parts` → `items.parts`），按编辑器口径去掉数组下标。 */
    private static String arrayPathOf(String prefix, String path) {
        if (prefix.isEmpty()) {
            return path;
        }
        return path.isEmpty() ? prefix : prefix + "." + path;
    }

    private static void collectUnknownSources(FieldMappingRule rule, RuleScope scope,
                                              Set<String> sample, List<RuleProblem> problems) {
        // 组节点和叶子都可能填了来源：组节点填的是来源数组，叶子填的是来源字段
        String source = sourceOf(rule);
        if (!source.isEmpty() && !"$index".equals(source) && scope.judgedAgainstRequestSample()) {
            String path = scope.sourcePath(source);
            if (!existsInSample(sample, path)) {
                // 话里带上「按样例算出来的那个路径」：子行的相对路径与 $root 只看写的那一串认不出重合
                problems.add(new RuleProblem(rule.target(), parentTarget(scope.parent()),
                        "来源字段不在样例里: " + source + "（按样例算是 " + path + "，字段清单里没有它）"));
            }
        }
        for (FieldMappingRule child : children(rule)) {
            collectUnknownSources(child, scope.child(rule), sample, problems);
        }
    }

    private static void collectMissingRequired(TargetFieldSpec target, String prefix, String arrayTarget,
                                               List<FieldMappingRule> parentRules, List<RuleProblem> problems) {
        String path = prefix.isEmpty() ? target.path() : prefix + "." + target.path();
        if (isArray(target)) {
            FieldMappingRule row = findRule(parentRules, path);
            if (row == null) {
                if (required(target)) {
                    problems.add(new RuleProblem(path, arrayTarget, "必填的目标字段没有配置来源: " + path));
                }
                return; // 数组那一行都没配，它的元素字段一条也进不来，不必逐个报
            }
            for (TargetFieldSpec element : target.element()) {
                collectMissingRequired(element, "", path, row.children(), problems);
            }
            return;
        }
        if (required(target) && findRule(parentRules, path) == null) {
            problems.add(new RuleProblem(path, arrayTarget, "必填的目标字段没有配置来源: " + path));
        }
    }

    private static void collectUncoveredEnumValues(FieldMappingRule rule, RuleScope scope,
                                                   Map<String, SourceField> sample, List<RuleProblem> problems) {
        if (rule.transform() == TransformType.ENUM && scope.judgedAgainstRequestSample()) {
            // 用的是来源字段在这份样例里的实际值（结构探测给的 samples），不试算表达式
            SourceField field = sample.get(scope.sourcePath(sourceOf(rule)));
            Set<String> missing = missingEnumKeys(rule.enumMap(), field);
            if (!missing.isEmpty()) {
                problems.add(new RuleProblem(rule.target(), parentTarget(scope.parent()),
                        "枚举表没有覆盖样例里出现过的值: " + String.join("、", missing)
                                + "（查不到键的来源值不会出现在目标报文里：这个字段整个消失）"));
            }
        }
        for (FieldMappingRule child : children(rule)) {
            collectUncoveredEnumValues(child, scope.child(rule), sample, problems);
        }
    }

    private static void collectTextIntoNumber(FieldMappingRule rule, RuleScope scope,
                                              Map<String, String> targetTypes, Map<String, SourceField> sample,
                                              List<RuleProblem> problems) {
        if (scope.judgedAgainstRequestSample()) {
            String target = scope.targetPath(rule.target());
            String targetType = targetTypes.get(target);
            SourceField source = sample.get(scope.sourcePath(sourceOf(rule)));
            if (writesTextIntoNumber(rule, targetType, source)) {
                problems.add(new RuleProblem(rule.target(), parentTarget(scope.parent()),
                        "目标字段 " + target + " 是数字类型（" + targetType + "），来源字段 "
                                + source.name() + " 是文本（" + source.type() + "）——建议套一层数字转换"));
            }
        }
        for (FieldMappingRule child : children(rule)) {
            collectTextIntoNumber(child, scope.child(rule), targetTypes, sample, problems);
        }
    }

    private static void collectEmptyConcatParts(FieldMappingRule rule, FieldMappingRule parent,
                                                List<RuleProblem> problems) {
        if (rule.transform() == TransformType.CONCAT && rule.parts() != null) {
            long empty = rule.parts().stream().filter(MappingSampleChecks::isEmptyPart).count();
            if (empty > 0) {
                problems.add(new RuleProblem(rule.target(), parentTarget(parent),
                        "拼接里有 " + empty + " 个空片段（这一行最后会拼出空字符串）"));
            }
        }
        for (FieldMappingRule child : children(rule)) {
            collectEmptyConcatParts(child, rule, problems);
        }
    }

    /**
     * 日期格式串：在样例值上跑一遍，或说明「没在样例上试过」。
     *
     * <p>组节点（数组行）不看：它的 transform 不参与编译（编译器只认它的 children），结构校验对组节点
     * 也是先走组那几条就 `continue`——拿它报一条运行时根本不会发生的问题只会是噪声。</p>
     */
    private static void collectDatePatternWarnings(FieldMappingRule rule, RuleScope scope,
                                                   Map<String, SourceField> sample, List<RuleProblem> problems) {
        if (children(rule).isEmpty() && rule.transform() == TransformType.DATE
                && DatePatterns.isLegal(rule.datePattern())) {
            checkDatePattern(rule, scope, sample, problems);
        }
        for (FieldMappingRule child : children(rule)) {
            collectDatePatternWarnings(child, scope.child(rule), sample, problems);
        }
    }

    /**
     * 一条规则的日期格式串：能试就试，试不了就说清为什么。
     *
     * <p>判不了这一行的三种情况分得很清：样例根本管不到这一行（没带样例 / 是别的步骤的）→ 给提示；
     * 样例里有这个字段但没取到值 → 也给提示；样例里有（说明这份样例管得到这一行）却没这个字段 →
     * 什么都不补，因为「来源字段不在样例里」那条**错误**已经在说这件事了，同一行上说两遍是噪声。</p>
     */
    private static void checkDatePattern(FieldMappingRule rule, RuleScope scope,
                                         Map<String, SourceField> sample, List<RuleProblem> problems) {
        String pattern = datePatternOf(rule);
        SourceField field = scope.judgedAgainstRequestSample()
                ? sample.get(scope.sourcePath(sourceOf(rule)))
                : null;
        if (field == null) {
            if (!sample.isEmpty() && scope.judgedAgainstRequestSample()) {
                return;
            }
            problems.add(new RuleProblem(rule.target(), parentTarget(scope.parent()),
                    notTriedDatePatternMessage(pattern, NO_SAMPLE)));
            return;
        }
        Set<String> values = distinctSamples(field);
        if (values.isEmpty()) {
            problems.add(new RuleProblem(rule.target(), parentTarget(scope.parent()),
                    notTriedDatePatternMessage(pattern, NO_VALUES)));
            return;
        }
        List<String> unformattable = values.stream()
                .filter(value -> !JsonataFunctions.canFormat(value, pattern))
                .toList();
        if (!unformattable.isEmpty()) {
            problems.add(new RuleProblem(rule.target(), parentTarget(scope.parent()),
                    unformattableDatePatternMessage(pattern, unformattable)));
        }
    }

    /** 这一行生效的格式串：没写就是编译器与运行时的默认值。 */
    private static String datePatternOf(FieldMappingRule rule) {
        String pattern = rule.datePattern();
        return pattern == null || pattern.isBlank() ? DatePatterns.DEFAULT : pattern.trim();
    }

    /** 样例值跑不出来时的说明——说清运行时会怎么处理（原样发出去，不报错）。 */
    private static String unformattableDatePatternMessage(String pattern, List<String> values) {
        return "日期格式串 " + pattern + " 在样例值上解析不出来: " + String.join("、", values)
                + "（运行时会原样发出去，不报错）";
    }

    /** 试都没试过时的说明（提示，不拦保存）。 */
    private static String notTriedDatePatternMessage(String pattern, String why) {
        return "日期格式串 " + pattern + " 没在样例上试过（" + why + "）——配错了要到目标系统那边才发现";
    }

    /** 试不了的一种成因：这份来源数据压根没有样例（或样例是别的步骤的）。 */
    private static final String NO_SAMPLE = "手上还没有这一步的来源样例";

    /** 试不了的另一种成因：样例里有这个字段，但没取到值。 */
    private static final String NO_VALUES = "样例里这个字段一条值都没取到";

    // ---------- 判断 ----------

    /**
     * 这一行会不会把文本原样写进数字类型的字段。
     *
     * <p>数字转换（{@code INT}）就是这里的解药，所以它不算；行号本身就是数字；常量是用户写死的值，
     * 是不是数字由那一行的字面量说了算，不在这里猜。来源字段没探到类型（不在样例里）也不报——
     * 「来源不在样例里」那条已经在说这件事了。</p>
     */
    private static boolean writesTextIntoNumber(FieldMappingRule rule, String targetType, SourceField source) {
        if (!isNumberType(targetType) || source == null || !isTextType(source.type())) {
            return false;
        }
        return rule.transform() != TransformType.INT
                && rule.transform() != TransformType.INDEX
                && rule.transform() != TransformType.CONSTANT;
    }

    private static Set<String> missingEnumKeys(Map<String, Object> enumMap, SourceField field) {
        if (field == null) {
            return Set.of();
        }
        Set<String> mapped = enumMap == null ? Set.of() : enumMap.keySet();
        Set<String> missing = new LinkedHashSet<>();
        for (String value : distinctSamples(field)) {
            if (!mapped.contains(value)) {
                missing.add(value);
            }
        }
        return missing;
    }

    /** 这一份样例里这个字段出现过的值（去重、按出现顺序；枚举表的键是字符串）。 */
    private static Set<String> distinctSamples(SourceField field) {
        Set<String> values = new LinkedHashSet<>();
        for (Object sample : field.samples() == null ? List.of() : field.samples()) {
            if (sample == null) {
                continue;
            }
            values.add(sample instanceof Map<?, ?> map && map.containsKey("value")
                    ? text(map.get("value"))
                    : text(sample));
        }
        return values;
    }

    private static boolean isEmptyPart(Object part) {
        if (part instanceof String text) {
            return text.isBlank();
        }
        if (part instanceof Map<?, ?> map) {
            // `path` 键在、值是空串才算这一条；键整个没写是结构校验那条错误的事
            return map.get("path") != null && map.get("path").toString().isBlank();
        }
        // 别的形状（数字、null…）结构校验会报「片段只能是字符串常量或 {"path": ...}」，不在这里重复
        return false;
    }

    // ---------- 定位 ----------

    /**
     * 样例里有没有这个来源路径。
     *
     * <p>数组本身是个例外：结构探测把数组展开成元素字段（`items[0].materialCode`），**不会**单独给出
     * `items` 这一条——所以组节点填的来源数组只能按「有没有以它开头的元素字段」判，直接查名字会把
     * 每一行正常的数组映射都报成「不在样例里」。</p>
     */
    private static boolean existsInSample(Set<String> sample, String source) {
        if (source.isEmpty()) {
            return true;
        }
        if (sample.contains(source)) {
            return true;
        }
        String prefix = source + ".";
        return sample.stream().anyMatch(name -> name.startsWith(prefix));
    }

    /** 找一条规则（只在顶层、或只在某个组节点的子规则里找）。 */
    private static FieldMappingRule findRule(List<FieldMappingRule> rules, String target) {
        if (rules == null) {
            return null;
        }
        for (FieldMappingRule rule : rules) {
            if (target.equals(rule.target())) {
                return rule;
            }
        }
        return null;
    }

    private static String parentTarget(FieldMappingRule parent) {
        return parent == null ? null : parent.target();
    }

    private static String sourceOf(FieldMappingRule rule) {
        return rule.source() == null ? "" : rule.source().trim();
    }

    private static List<FieldMappingRule> children(FieldMappingRule rule) {
        return rule.children() == null ? List.of() : rule.children();
    }

    private static List<FieldMappingRule> rules(FieldMappingConfig config) {
        return config.rules() == null ? List.of() : config.rules();
    }

    private static boolean isArray(TargetFieldSpec target) {
        return target.element() != null && !target.element().isEmpty();
    }

    private static boolean required(TargetFieldSpec target) {
        return Boolean.TRUE.equals(target.required());
    }

    // ---------- 目标清单与样例的读法 ----------

    private static List<TargetFieldSpec> targets(MappingDraftRequest request) {
        return request.targets() == null ? List.of() : request.targets();
    }

    /** 目标字段路径 → 类型（数组元素里的按完整路径摊平，与草稿里的目标字段同一个口径）。 */
    private static Map<String, String> targetTypes(List<TargetFieldSpec> targets) {
        Map<String, String> types = new LinkedHashMap<>();
        collectTypes(targets, "", types);
        return types;
    }

    private static void collectTypes(List<TargetFieldSpec> targets, String prefix, Map<String, String> types) {
        for (TargetFieldSpec target : targets) {
            String path = prefix.isEmpty() ? target.path() : prefix + "." + target.path();
            if (target.type() != null && !target.type().isBlank()) {
                types.putIfAbsent(path, target.type());
            }
            collectTypes(target.element() == null ? List.of() : target.element(), path, types);
        }
    }

    /** 样例里的字段路径，按编辑器的口径归一（去掉数组下标）。 */
    private static Set<String> sampleNames(MappingDraftRequest request) {
        Set<String> names = new LinkedHashSet<>();
        for (SourceField field : sample(request)) {
            if (field.name() != null && !field.name().isBlank()) {
                names.add(normalizePath(field.name()));
            }
        }
        return names;
    }

    /**
     * 样例里的字段，键按编辑器的口径归一（{@code items[0].unit} → {@code items.unit}）。
     *
     * <p>{@link RuleScope#sourcePath} 算出来的路径就是这个口径，两边得用同一种键才查得到——数组元素里的
     * 枚举值、类型判断都靠它。</p>
     */
    private static Map<String, SourceField> sampleFields(MappingDraftRequest request) {
        Map<String, SourceField> fields = new LinkedHashMap<>();
        for (SourceField field : sample(request)) {
            if (field.name() != null && !field.name().isBlank()) {
                fields.putIfAbsent(normalizePath(field.name()), field);
            }
        }
        return fields;
    }

    private static List<SourceField> sample(MappingDraftRequest request) {
        return request.sourceFields() == null ? List.of() : request.sourceFields();
    }

    /** 探测路径折成编辑器口径：`items[0].materialCode` → `items.materialCode`。 */
    private static String normalizePath(String path) {
        return path.replaceAll("\\[\\d+\\]", "");
    }

    /**
     * 库里那个列类型算不算数字类型（文本类型同理）。
     *
     * <p>认不出来的类型一律当「不是」：这条检查靠类型名匹配，宁可不报，也不要拿一个似是而非的
     * 类型名去误伤一份正常的配置（探不到类型时同理）。</p>
     */
    private static boolean isNumberType(String type) {
        return typeTokens(type).stream().anyMatch(NUMBER_TYPES::contains);
    }

    /** 库里那个列类型算不算文本类型（样例探测给的是 STRING）。 */
    private static boolean isTextType(String type) {
        return typeTokens(type).stream().anyMatch(TEXT_TYPES::contains);
    }

    private static final Set<String> NUMBER_TYPES = Set.of(
            "INT", "INTEGER", "TINYINT", "SMALLINT", "MEDIUMINT", "BIGINT",
            "DECIMAL", "NUMERIC", "NUMBER", "FLOAT", "DOUBLE", "REAL",
            "BIT", "SERIAL", "YEAR");

    private static final Set<String> TEXT_TYPES = Set.of(
            "CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "TEXT", "TINYTEXT",
            "MEDIUMTEXT", "LONGTEXT", "CLOB", "STRING", "UUID", "JSON");

    /** 类型名的两种写法都认：连写的（`VARCHAR`）与带分隔的（`VARCHAR(64)`／`UNSIGNED BIGINT`）。 */
    private static Set<String> typeTokens(String type) {
        if (type == null || type.isBlank()) {
            return Set.of();
        }
        String upper = type.toUpperCase();
        Set<String> tokens = new TreeSet<>(List.of(upper.split("[^A-Z0-9]+")));
        tokens.add(upper.replaceAll("[^A-Z0-9]", ""));
        tokens.remove("");
        return tokens;
    }

    /** 样例值的文本形状（枚举表的键就是字符串）。 */
    private static String text(Object value) {
        if (value instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue()); // 键是字符串，1.0 与 "1" 该算同一个键
        }
        return String.valueOf(value);
    }
}
