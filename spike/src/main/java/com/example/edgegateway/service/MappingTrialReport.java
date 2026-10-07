package com.example.edgegateway.service;

import com.example.edgegateway.config.action.FieldMappingConfig;
import com.example.edgegateway.config.action.FieldMappingRule;
import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.web.dto.MappingDraftRequest.TargetFieldSpec;
import com.example.edgegateway.web.dto.MappingTrialResult.Failure;
import com.example.edgegateway.web.dto.MappingTrialResult.FieldState;
import com.example.edgegateway.web.dto.MappingTrialResult.FieldStatus;
import com.example.edgegateway.web.dto.MappingTrialResult.Metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 「试算」的逐字段状态表（#120）：目标报文里**少了一个键**时，说清是哪一种少法。
 *
 * <p>从前端那个 JSON 看，三件事长得一模一样（键都不在）：</p>
 * <ol>
 *   <li><b>草稿里压根没有这条规则</b>——只有拿目标清单比才知道「清单里有 5 个、你配了 4 个」（{@code UNMAPPED}）；</li>
 *   <li><b>配了，但来源路径在这份样例里取不到值</b>——引擎在拼装目标对象时**把键丢掉**（不是变成 null）；</li>
 *   <li><b>配了，但枚举表查不到键</b>——同上，字段整个消失。</li>
 * </ol>
 *
 * <p>还有第四种，它的键**在**结果里、值就是 null（{@code NULL}）：那是「算出来就是 null」，与上面三种
 * 完全不是一回事。把它并进「落值」或者并进「被丢掉」，界面上就把一件真事说反了——这是本票的核心价值。</p>
 *
 * <p><b>不动引擎、不动编译器</b>：{@link JsonataFieldMapper} 只做「整棵树编译成一条表达式、跑一次」，
 * 没有逐字段口子。所以这里走的是**结果对照**：拿整份跑出来的报文，按目标路径去问「这个键在不在」，
 * 键不在时再用 {@link #wrap} 把这一行单独跑一遍（与整份抛错时的逐行归因同一套机制），
 * 判断它是**表达式报错**还是**静默丢掉**——后者的 {@code reason} 就用引擎原话，
 * 前者之外才另说一句。</p>
 *
 * <p>手写表达式（{@code mode=EXPRESSION}）那一边没有「行」：{@code fields} 按表达式实际产出的键给，
 * {@code UNMAPPED} 不判。</p>
 */
final class MappingTrialReport {

    /** 「这一格在结果里没有」的哨兵：与「键在、值是 null」是两件事，不能都用 null 表示。 */
    private static final Object ABSENT = new Object();

    /** 探来源值用的假目标字段：只活在这一份临时编译产物里，带上下划线不与真目标撞。 */
    private static final String PROBE = "__trial_probe__";

    private MappingTrialReport() {
    }

    /** 一次报告：逐字段状态 + 一句整合数。 */
    record Report(List<FieldStatus> fields, Metrics metrics) {
    }

    // ---------- 入口 ----------

    /**
     * 拿整份跑出来的那份报文，给每个目标字段定一档状态。
     *
     * <p>行从三处来，按这个顺序合并（同一格先来的赢）：</p>
     * <ol>
     *   <li><b>目标清单</b>——清单里有、草稿里没配的字段靠它才占得上一行（{@code UNMAPPED}）；
     *       手写表达式那一边不取它（票里写明「{@code UNMAPPED} 不判」）。</li>
     *   <li><b>这份报文本身</b>——规则算出来的字段清单里可能没有（手输路径、表达式多给的键），
     *       它在界面上同样要占一行，不然「证据表」和右边那份 JSON 对不上。</li>
     *   <li><b>草稿里的规则</b>——配了却没落值的字段（{@code DROPPED}）只有从规则这一侧才看得出来。</li>
     * </ol>
     */
    static Report report(FieldMappingConfig config, List<TargetFieldSpec> targetFields, Object output,
                         Map<Integer, Object> context) {
        boolean expression = config.isExpression();
        Map<String, RuleSite> sites = expression ? Map.of() : ruleSites(config);

        Map<String, Slot> slots = new LinkedHashMap<>();
        if (!expression) {
            for (Slot slot : targetSlots(targetFields)) {
                slots.putIfAbsent(slot.key(), slot);
            }
        }
        for (Slot slot : outputSlots(output)) {
            slots.putIfAbsent(slot.key(), slot);
        }
        if (!expression) {
            for (RuleSite site : sites.values()) {
                Slot slot = new Slot(site.rule().target(), site.parent(), site.group());
                slots.putIfAbsent(slot.key(), slot);
            }
        }

        Values values = new Values(output, slots);
        List<FieldStatus> fields = new ArrayList<>();
        int targets = 0;
        int valued = 0;
        int dropped = 0;
        int unmapped = 0;
        int arrayRows = 0;
        for (Slot slot : slots.values()) {
            FieldStatus status = statusOf(slot, sites, values, context);
            fields.add(status);
            if (status.arrayRows() != null) {
                arrayRows += status.arrayRows();
            }
            // 组行不是「一个目标字段」：它只是元素字段那一组的标题（它的元素字段才是叶子）。
            if (!slot.group()) {
                targets++;
                switch (status.status()) {
                    case VALUED, NULL -> valued++;
                    case DROPPED -> dropped++;
                    case UNMAPPED -> unmapped++;
                }
            }
        }
        return new Report(fields, new Metrics(targets, valued, dropped, unmapped, arrayRows));
    }

    // ---------- 逐字段定档 ----------

    private static FieldStatus statusOf(Slot slot, Map<String, RuleSite> sites, Values values,
                                        Map<Integer, Object> context) {
        RuleSite site = sites.get(slot.key());
        Found found = values.found(slot);
        // 数组组行一律给 arrayRows（没落值就是 0）：界面靠它认「这一行是数组组行、它落了几行」
        Integer rows = slot.group() ? 0 : null;
        if (found.present() == 0) {
            if (site == null) {
                // 走到这里只可能是「清单里有、草稿里没有」（报文那一侧的行都是照着报文建出来的）
                return new FieldStatus(slot.target(), slot.parent(), null, FieldState.UNMAPPED, null, rows);
            }
            return new FieldStatus(slot.target(), slot.parent(), null, FieldState.DROPPED,
                    reasonOf(slot, site, context), rows);
        }
        // 键在结果里：值是 null 也是一档自己的状态（「算出来就是 null」），不是「被丢掉」
        FieldState state = found.values().stream().allMatch(Objects::isNull)
                ? FieldState.NULL : FieldState.VALUED;
        if (slot.group()) {
            // 组行不给值：它那一格要说的是「落了几行」，整份数组摆进表里只会把别的行挤没
            return new FieldStatus(slot.target(), slot.parent(), null, state, null, found.rows());
        }
        // 一个容器（顶层字段）给那个值本身；数组元素字段**一律给一组取值**——哪怕这个数组只落了一行，
        // 也不能退化成标量（界面上要靠「是不是数组」认出「按元素分开给」这一档）
        Object value = slot.parent() == null ? found.values().get(0) : found.values();
        return new FieldStatus(slot.target(), slot.parent(), value, state, null, null);
    }

    /**
     * 这个键为什么不在结果里。
     *
     * <p>先把这一行**单独跑一遍**：跑不通就用引擎那句原话（这一行自己有问题，与「整份那次抛错」
     * 是两回事）；跑得通却仍然没有这个键，说明是**静默丢掉**——枚举查不到键、来源取不到值都会这样，
     * 界面得自己说清是哪一种。枚举那条用与配置期校验逐字一样的说法（同一条事实，两处不各写一套）；
     * 其余用「来源 X 在这份样例里取不到值」。</p>
     */
    private static String reasonOf(Slot slot, RuleSite site, Map<Integer, Object> context) {
        String error = runAlone(site, context);
        if (error != null) {
            return error;
        }
        FieldMappingRule rule = site.rule();
        if (rule.transform() == TransformType.ENUM) {
            Object source = sourceValueOf(site, context);
            return source == null
                    ? "枚举表没有覆盖样例里取到的值（查不到键的来源值不会出现在目标报文里：这个字段整个消失）"
                    : "枚举表没有覆盖样例里出现过的值: " + source
                            + "（查不到键的来源值不会出现在目标报文里：这个字段整个消失）";
        }
        String from = rule.source();
        if (from == null || from.isBlank()) {
            return "这一行算出来的值在结果里没有落点，字段被丢掉了";
        }
        return slot.group()
                ? "来源 " + from + " 在这份样例里取不到值，一行都没落"
                : "来源 " + from + " 在这份样例里取不到值，字段被丢掉了";
    }

    /**
     * 这一行的来源在这份样例里取到的值——只给枚举那条 {@code reason} 用（好把那个没覆盖到的键说出来）。
     *
     * <p>枚举查不到键时 {@code $lookup} 给的是 undefined，{@code reason} 里就少了最关键的那个值。
     * 所以这里把同一行的转换换成「直接取值」再跑一遍：值取得到就拿得到，取不到就返回 null
     * （来源本身也取不到值时，那句 reason 不去编一个值出来）。</p>
     */
    private static Object sourceValueOf(RuleSite site, Map<Integer, Object> context) {
        String from = site.rule().source();
        if (from == null || from.isBlank()) {
            return null;
        }
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("sourceStep", site.step());
        probe.put("source", from);
        probe.put("target", PROBE);
        probe.put("transform", TransformType.STRING.name());
        try {
            Object output = new JsonataFieldMapper(wrap(site.ancestors(), FieldMappingRule.from(probe)))
                    .apply(context);
            Object value = findKey(output, PROBE);
            return value == ABSENT ? null : value;
        } catch (RuntimeException e) {
            // 探不出来不是错：那句 reason 退回不带具体值的那一版
            return null;
        }
    }

    // ---------- 逐行归因（#116，两段式的第二段） ----------

    /**
     * 整份没跑通时：逐行单独跑一遍，报出**单独跑也失败**的那些行。
     *
     * <p>手写表达式那一边没有行（那边只有一整段表达式），所以归因列表是空的——界面上就只显示整份那句错。</p>
     */
    static List<Failure> failures(FieldMappingConfig config, Map<Integer, Object> context) {
        if (config.isExpression()) {
            return List.of();
        }
        List<Failure> failures = new ArrayList<>();
        for (FieldMappingRule rule : config.rules()) {
            attribute(rule, List.of(), null, context, failures);
        }
        return failures;
    }

    /**
     * 一行一行地归因。
     *
     * <p>单独跑这一行跑通了就跳过它（错不在这里）。没跑通而它是个数组组行时，再把它的每个子行**单独放回
     * 这个组里**跑一遍，报那些单独跑也失败的子行（这才是「哪一行有问题」）。嵌套组同理递归下去。</p>
     *
     * <p>一个子行单独跑都没失败、只有组行自己不成时，**什么都不报**：报出组行等于说「这一行单独跑也
     * 不成」，而这一行的不成功正是整份那次抛错的同一个原因，指不出更细的东西。逐行跑只是定位手段，
     * 定位不到就不硬指一行——整份那句错才是真话（ADR-0012 决策 6）。</p>
     *
     * @param ancestors    外层组链（从外到内），单独跑这一行时要把它们套回来
     * @param parentTarget 这一行所属数组行的目标字段（顶层为 null），与草稿校验那边同一个定位口径
     */
    private static void attribute(FieldMappingRule rule, List<FieldMappingRule> ancestors, String parentTarget,
                                  Map<Integer, Object> context, List<Failure> failures) {
        String message = runAlone(wrap(ancestors, rule), context);
        if (message == null) {
            return; // 单独跑这一行没问题：错的不是它
        }
        boolean group = rule.children() != null && !rule.children().isEmpty();
        if (!group) {
            failures.add(new Failure(rule.target(), parentTarget, message));
            return;
        }
        List<FieldMappingRule> chain = new ArrayList<>(ancestors);
        chain.add(rule);
        for (FieldMappingRule child : rule.children()) {
            attribute(child, chain, rule.target(), context, failures);
        }
    }

    // ---------- 单独跑一条规则 ----------

    /**
     * 把一条候选行包成**能单独编译执行**的一份规则。
     *
     * <p>顶层行原样就能跑；数组子行不行——它的来源写的是相对路径（编译出来是 {@code $g0.materialCode}），
     * 单独编出来 {@code $g0} 根本没绑定。所以每一层外层组都按「只有这一个子行」重建一份套回去
     * （{@link FieldMappingRule#withChildren}）：组的来源数组、{@code $map} 与变量绑定都还在，
     * 跑的就真是这一行。</p>
     */
    private static List<FieldMappingRule> wrap(List<FieldMappingRule> ancestors, FieldMappingRule rule) {
        FieldMappingRule current = rule;
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            current = ancestors.get(i).withChildren(List.of(current));
        }
        return List.of(current);
    }

    /** 单独跑这一条：跑通了返回 null，没跑通返回引擎那句话。 */
    private static String runAlone(RuleSite site, Map<Integer, Object> context) {
        return runAlone(wrap(site.ancestors(), site.rule()), context);
    }

    private static String runAlone(List<FieldMappingRule> rules, Map<Integer, Object> context) {
        try {
            new JsonataFieldMapper(rules).apply(context);
            return null;
        } catch (Exception e) {
            return messageOf(e);
        }
    }

    /** 抛出来的那句话；引擎偶尔给个没有 message 的异常，那时至少说清是哪一类。 */
    static String messageOf(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    // ---------- 规则一侧：一行在哪、它的外层组是谁 ----------

    /** 一条规则在草稿里的位置：它自己、它的外层组链（单独跑时要套回去）、它属于哪个数组行。 */
    private record RuleSite(FieldMappingRule rule, List<FieldMappingRule> ancestors, String parent, int step) {

        boolean group() {
            return rule.children() != null && !rule.children().isEmpty();
        }
    }

    /** 草稿里的每一行，按「所属数组行 + 目标字段」建索引——与校验结果那份定位口径同一个键。 */
    private static Map<String, RuleSite> ruleSites(FieldMappingConfig config) {
        Map<String, RuleSite> sites = new LinkedHashMap<>();
        collectSites(config.rules(), List.of(), null, 0, sites);
        return sites;
    }

    private static void collectSites(List<FieldMappingRule> rules, List<FieldMappingRule> ancestors,
                                     String parentTarget, int step, Map<String, RuleSite> sites) {
        if (rules == null) {
            return;
        }
        for (FieldMappingRule rule : rules) {
            if (rule == null || rule.target() == null) {
                continue;
            }
            // 组内子行不写 sourceStep（继承组节点的）：单独跑时也得按继承来，否则取的是 $s_0
            int own = rule.sourceStep() != null ? rule.sourceStep() : step;
            sites.put(key(parentTarget, rule.target()), new RuleSite(rule, ancestors, parentTarget, own));
            if (rule.children() != null && !rule.children().isEmpty()) {
                List<FieldMappingRule> chain = new ArrayList<>(ancestors);
                chain.add(rule);
                collectSites(rule.children(), chain, rule.target(), own, sites);
            }
        }
    }

    // ---------- 目标清单一侧 ----------

    private static List<Slot> targetSlots(List<TargetFieldSpec> targetFields) {
        List<Slot> slots = new ArrayList<>();
        collectTargets(targetFields, null, slots);
        return slots;
    }

    /**
     * 目标清单摊成一行一格。
     *
     * <p>数组字段自己占一个组行，它的元素字段**写相对路径**、{@code parent} 指向这个数组行——与
     * 「校验配置」里 {@code collectMissingRequired} 的口径逐字一样（那边也是元素带空前缀往下走）。</p>
     */
    private static void collectTargets(List<TargetFieldSpec> specs, String arrayTarget, List<Slot> slots) {
        if (specs == null) {
            return;
        }
        for (TargetFieldSpec spec : specs) {
            if (spec == null || spec.path() == null || spec.path().isBlank()) {
                continue;
            }
            boolean group = spec.element() != null && !spec.element().isEmpty();
            slots.add(new Slot(spec.path(), arrayTarget, group));
            if (group) {
                collectTargets(spec.element(), spec.path(), slots);
            }
        }
    }

    // ---------- 报文一侧 ----------

    /**
     * 这份报文里实际产出的字段，按目标路径摊成一行一格。
     *
     * <p>它在两处顶用：草稿里配了、清单里没有的字段（手输路径、表达式多算的键）也在证据表里占一行；
     * 手写表达式那一边干脆只有它（那边没有「行」）。</p>
     *
     * <p>数组元素里的字段写相对路径、{@code parent} 指向那个数组——与目标清单那一侧同一个口径，
     * 界面上才不用认两种路径。</p>
     *
     * @param prefix 中间层对象的路径前缀（顶层是空串；数组元素里从空串重新起算）
     */
    private static List<Slot> outputSlots(Object output) {
        List<Slot> slots = new ArrayList<>();
        collectOutput(output, null, "", slots);
        return slots;
    }

    private static void collectOutput(Object node, String arrayTarget, String prefix, List<Slot> slots) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String target = prefix.isEmpty() ? name : prefix + "." + name;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                // 中间层对象自己不占一行：它是下一层的路径前缀（与编译器建的目标树一致）
                collectOutput(value, arrayTarget, target, slots);
            } else if (value instanceof List<?> list) {
                slots.add(new Slot(target, arrayTarget, true));
                for (Object item : list) {
                    collectOutput(item, target, "", slots);
                }
            } else {
                slots.add(new Slot(target, arrayTarget, false));
            }
        }
    }

    // ---------- 往报文里找一格 ----------

    /** 表里的一格：目标字段 + 它所属的数组行（顶层为 null）+ 它是数组组行吗。 */
    private record Slot(String target, String parent, boolean group) {

        String key() {
            return MappingTrialReport.key(parent, target);
        }
    }

    /**
     * 一格在报文里找出来的样子。
     *
     * @param present 几个容器里有这个键（顶层字段最多一个；数组元素字段是元素里的命中数）
     * @param values  按容器对齐的取值（没有这个键的那一格是 null）；{@code present == 0} 时是空列表
     * @param rows    这一格往下落的数组行数（只有组行用得上）
     */
    private record Found(int present, List<Object> values, int rows) {
    }

    /**
     * 「这一格在报文里吗」的解析器：同一格只算一次（数组元素字段要按元素逐个问，问一次就够）。
     *
     * <p>一格的容器是什么，取决于它有没有所属数组行：顶层字段的容器就是报文根；数组元素字段的容器是
     * 那个数组落下来的每个元素对象。所以先要一个「某个数组行落下来哪些元素对象」的口子，
     * 而那个口子本身又要递归（数组套数组）。</p>
     */
    private static final class Values {

        private final Object output;
        /** 数组行的目标字段 → 那一格（元素字段靠它找自己的容器）。 */
        private final Map<String, Slot> groups = new LinkedHashMap<>();
        private final Map<String, Found> cache = new LinkedHashMap<>();

        Values(Object output, Map<String, Slot> slots) {
            this.output = output;
            for (Slot slot : slots.values()) {
                if (slot.group()) {
                    groups.putIfAbsent(slot.target(), slot);
                }
            }
        }

        Found found(Slot slot) {
            return cache.computeIfAbsent(slot.key(), key -> lookUp(slot));
        }

        private Found lookUp(Slot slot) {
            if (slot.parent() == null) {
                Object value = navigate(output, slot.target());
                if (value == ABSENT) {
                    return new Found(0, List.of(), 0);
                }
                return new Found(1, Collections.singletonList(value), rowsOf(value));
            }
            List<Map<String, Object>> elements = elementsOf(slot.parent());
            if (elements == null) {
                // 所属数组行自己就没落值：它的元素字段跟着没有落点
                return new Found(0, List.of(), 0);
            }
            List<Object> values = new ArrayList<>();
            int present = 0;
            int rows = 0;
            for (Map<String, Object> element : elements) {
                Object value = navigate(element, slot.target());
                if (value == ABSENT) {
                    values.add(null);
                    continue;
                }
                present++;
                values.add(value);
                rows += rowsOf(value);
            }
            return new Found(present, values, rows);
        }

        /**
         * 这个数组行落下来的元素对象；这一格没落值（键不在）时给 null，落了但不含对象时给空列表。
         *
         * <p>往上找一层就够：一格的 {@code parent} 只写它**直属**的那一层数组行（与草稿校验同一个
         * 定位口径），而数组组行总在它的元素字段之前进表，所以 {@link #groups} 里取到的总是外层那一份。
         * 这条链有限、不会绕回来，不需要深度护栏。嵌套数组（数组元素里还有数组）不在编辑器支持的
         * 范围里（见功能文档的「待办 / 已知限制」），这里按一层算。</p>
         */
        private List<Map<String, Object>> elementsOf(String parentTarget) {
            Slot group = groups.get(parentTarget);
            if (group == null) {
                return null;
            }
            Found found = found(group);
            if (found.present() == 0) {
                return null;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object value : found.values()) {
                if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            out.add(stringKeyed(map));
                        }
                    }
                }
            }
            return out;
        }
    }

    // ---------- 小工具 ----------

    /** 按点号路径往下取一格；中间任何一段不在（或不是对象）就当整格不在——与 JSONata 取到 undefined 同理。 */
    private static Object navigate(Object node, String path) {
        if (path == null || path.isBlank()) {
            return ABSENT;
        }
        Object current = node;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return ABSENT;
            }
            current = map.get(segment);
        }
        return current;
    }

    /** 递归找第一个带这个键的对象里的值（探测来源值时用：那一份临时产物只有一行，找得到就是它）。 */
    private static Object findKey(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
            for (Object value : map.values()) {
                Object found = findKey(value, key);
                if (found != ABSENT) {
                    return found;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                Object found = findKey(item, key);
                if (found != ABSENT) {
                    return found;
                }
            }
        }
        return ABSENT;
    }

    private static int rowsOf(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 一格的键：「所属数组行 + 目标字段」——与前端 `draft.ts` 里那个定位键同一个算法。 */
    private static String key(String parent, String target) {
        return (parent == null ? "" : parent) + "\u0000" + (target == null ? "" : target);
    }
}
