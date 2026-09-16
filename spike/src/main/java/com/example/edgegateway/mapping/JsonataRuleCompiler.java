package com.example.edgegateway.mapping;

import com.example.edgegateway.config.FieldMappingRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化字段映射规则 → JSONata 表达式编译器（DS-10.2 S1）。
 *
 * <p>存储与执行分离：rules 是唯一存储契约，本类在路由生成时把规则树编译为 JSONata 表达式，
 * 由 {@link JsonataFieldMapper} 执行。生成规则：</p>
 * <ul>
 *   <li>目标路径按点号分层 → 嵌套对象构造器；叶子为目标路径末段的转换表达式。</li>
 *   <li>顶层来源统一为变量 $s_&lt;sourceStep&gt;（0 = 任务输入），执行时按上下文绑定；
 *       组内子规则相对当前数组元素（$g0/$g1/...），行号用 $i0/$i1/...。</li>
 *   <li>特殊来源 $root = 当前 sourceStep 的根对象（组内跨层级引用头字段）。</li>
 *   <li>CONSTANT → 字符串字面量；CONCAT → &amp; 拼接；ENUM → $lookup；INT → $number；
 *       DATE → $dateFormat(自定义函数)；INDEX → $i&lt;d&gt; + (startAt-1)。</li>
 * </ul>
 */
public final class JsonataRuleCompiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 没写格式串时用的默认值（与运行时、校验共用 {@link DatePatterns#DEFAULT} 这一个定义）。 */
    private static final String DEFAULT_DATE_PATTERN = DatePatterns.DEFAULT;

    private JsonataRuleCompiler() {
    }

    /** 编译规则列表为 JSONata 表达式（顶层是目标对象构造器）。 */
    public static String compile(List<FieldMappingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new CompileException(null, null, "字段映射动作需要至少一条映射规则");
        }
        TargetNode root = new TargetNode();
        for (FieldMappingRule rule : rules) {
            insert(root, rule, rule.sourceStep() != null ? rule.sourceStep() : 0, 0, null);
        }
        StringBuilder sb = new StringBuilder("{\n");
        renderChildren(root, sb);
        sb.append("\n}");
        return sb.toString();
    }

    /**
     * 编译期发现的规则树问题：缺少目标字段 / 目标路径不合法 / 前缀冲突 / 重复映射 / 拼接没片段。
     *
     * <p>带上出错的那条规则的目标字段和它所属组节点的目标字段——「校验配置」那个只读接口（#103）
     * 据此把问题定位到界面上的一行（数组子行的目标写的是相对路径，光有它认不出是哪一行）。
     * 仍然是 {@link IllegalArgumentException}，原来的调用方（路由构建、旧测试）不受影响。</p>
     */
    public static final class CompileException extends IllegalArgumentException {

        private final String target;
        private final String parent;

        CompileException(String target, String parent, String message) {
            super(message);
            this.target = target;
            this.parent = parent;
        }

        /** 出错那条规则的目标字段；定位不到某一条规则（整份规则表级别）时为 null。 */
        public String target() {
            return target;
        }

        /** 出错那条规则所属组节点的目标字段；顶层规则为 null。 */
        public String parent() {
            return parent;
        }
    }

    // ---------- 目标树构建 ----------

    /**
     * 把一条规则按其 target 点号路径插入目标树；组节点用 children 递归继续。
     *
     * @param parentTarget 这一条所属组节点的目标字段（顶层为 null），只用来报错时定位
     */
    private static void insert(TargetNode parent, FieldMappingRule rule, int step, int depth, String parentTarget) {
        if (rule.target() == null || rule.target().isBlank()) {
            // 目标字段整条空着：说成「缺少目标字段」，别让下面那句 split 抛 NPE——NPE 不是 CompileException，
            // 「校验配置」那条链路接不住，一条草稿问题会变成 500。保存那道门（FieldMappingConfig.collectProblems）
            // 对同一种输入说的也是这一句，两处一致，服务那边才能把同一条问题合成一条（#114）
            throw new CompileException(rule.target(), parentTarget, "映射规则缺少目标字段 target");
        }
        String[] segments = rule.target().split("\\.");
        if (segments.length == 0 || segments[0].isBlank()) {
            throw new CompileException(rule.target(), parentTarget, "目标字段路径不合法: " + rule.target());
        }
        TargetNode node = parent;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isBlank()) {
                throw new CompileException(rule.target(), parentTarget, "目标字段路径不合法: " + rule.target());
            }
            TargetNode next = node.children.get(seg);
            if (i < segments.length - 1 && next != null && next.leaf != null && !next.leaf.group) {
                // 中间节点已有非组叶子：如先映射 "a.b" 再映射 "a.b.c"，c 会被静默丢弃
                throw new CompileException(rule.target(), parentTarget, "目标字段前缀冲突: " + rule.target());
            }
            node = next != null ? next : node.children.computeIfAbsent(seg, k -> new TargetNode());
        }
        boolean group = rule.children() != null && !rule.children().isEmpty();
        if (node.leaf != null) {
            throw new CompileException(rule.target(), parentTarget, "目标字段重复映射: " + rule.target());
        }
        if (!node.children.isEmpty() && !group) {
            throw new CompileException(rule.target(), parentTarget, "目标字段前缀冲突: " + rule.target());
        }
        node.leaf = new Leaf(rule, step, depth, group);
        if (group) {
            for (FieldMappingRule child : rule.children()) {
                insert(node, child, step, depth + 1, rule.target());
            }
        }
    }

    // ---------- 渲染 ----------

    private static void renderChildren(TargetNode node, StringBuilder sb) {
        boolean first = true;
        for (Map.Entry<String, TargetNode> entry : node.children.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  ").append(jsonString(entry.getKey())).append(": ");
            TargetNode child = entry.getValue();
            if (child.leaf == null) {
                // 中间层节点：嵌套对象
                sb.append("{\n");
                renderChildren(child, sb);
                sb.append("\n  }");
            } else if (child.leaf.group) {
                sb.append(groupExpr(child.leaf.rule, child.leaf.step, child.leaf.depth));
            } else {
                sb.append(leafExpr(child.leaf.rule, child.leaf.step, child.leaf.depth));
            }
        }
    }

    /** 组节点：$map(来源数组, function($g<d>, $i<d>) { { 子映射 } })。 */
    private static String groupExpr(FieldMappingRule group, int step, int depth) {
        String src = sourceExpr(group.source(), step, depth);
        StringBuilder inner = new StringBuilder();
        for (FieldMappingRule child : group.children()) {
            if (inner.length() > 0) {
                inner.append(",\n");
            }
            inner.append("    ").append(jsonString(child.target())).append(": ")
                    .append(childExpr(child, step, depth + 1));
        }
        // 数组构造器包装：$map 单元素结果会被 JSONata 序列扁平化（退化为对象），
        // 包一层 [...] 保证组输出恒为数组（S0 实测发现）
        return "[ $map(" + src + ", function($g" + depth + ", $i" + depth + ") { {\n"
                + inner + "\n  } }) ]";
    }

    /** 组内子规则：嵌套组继续 $map，叶子直接转换。 */
    private static String childExpr(FieldMappingRule rule, int step, int depth) {
        if (rule.children() != null && !rule.children().isEmpty()) {
            return groupExpr(rule, step, depth);
        }
        return leafExpr(rule, step, depth);
    }

    /** 叶子转换表达式。depth = 嵌套组层数（0 = 顶层）。 */
    private static String leafExpr(FieldMappingRule rule, int step, int depth) {
        return switch (rule.transform()) {
            case CONSTANT -> jsonString(rule.constant());
            case CONCAT -> concatExpr(rule, step, depth);
            case INT -> "$number(" + sourceExpr(rule.source(), step, depth) + ")";
            case DATE -> "$dateFormat(" + sourceExpr(rule.source(), step, depth) + ", "
                    + jsonString(datePatternOf(rule)) + ")";
            case ENUM -> "$lookup(" + enumLiteral(rule.enumMap()) + ", " + sourceExpr(rule.source(), step, depth) + ")";
            case INDEX -> indexExpr(depth, rule.startAt());
            default -> sourceExpr(rule.source(), step, depth);
        };
    }

    /**
     * 这一行生效的格式串：没写（键不在、空串、只有空白）都用默认值。
     *
     * <p>空串也当「没写」而不是「写了一个空格式串」——编辑器清空手输框时不会把空串写进规则，
     * 但手写/存量配置里可能是这个形状，送进去的话目标收到的是一串空（#111 验收五）。</p>
     */
    private static String datePatternOf(FieldMappingRule rule) {
        String pattern = rule.datePattern();
        return pattern == null || pattern.isBlank() ? DEFAULT_DATE_PATTERN : pattern;
    }

    /**
     * CONCAT：片段按顺序拼接，产物仍然是一段人眼能读的 `&` 串（#112 验收六）。
     *
     * <p>片段有两种写法：字符串常量，或一个对象。对象不带 `transform`（老形状 `{"path": "..."}`）就是
     * 「直接取值」——老配置的退化情形，编译结果逐字节不变。</p>
     */
    private static String concatExpr(FieldMappingRule rule, int step, int depth) {
        List<String> parts = new ArrayList<>();
        List<Object> raw = rule.parts() == null ? List.of() : rule.parts();
        for (int i = 0; i < raw.size(); i++) {
            Object part = raw.get(i);
            if (part instanceof String s) {
                parts.add(jsonString(s));
            } else if (part instanceof Map<?, ?> m) {
                parts.add(partExpr(rule, m, i + 1, step, depth));
            } else {
                // 认不出的片段（不是字符串、也不是对象）：报清楚是哪一个，别在下面抛 NPE——
                // 「校验配置」那条链路要靠这条异常变成一条问题
                throw new CompileException(rule.target(), null,
                        partAt(i + 1, "片段只能是字符串常量或一个对象") + ": " + rule.target());
            }
        }
        if (parts.isEmpty()) {
            throw new CompileException(rule.target(), null, "CONCAT 转换需要 parts 片段: " + rule.target());
        }
        return String.join(" & ", parts);
    }

    /**
     * 一个片段（#112）：按它自己挂的那一个转换编译，参数就是那一份。
     *
     * <p>允许的片段转换五种：直接取值 / 数字转换 / 日期格式化 / 枚举映射 / 行号。不给拼接（拼接是结合的，
     * 摊平即可）与数组迭代（会改变片段数量）；一个片段只能挂一个转换，所以这里没有嵌套。</p>
     */
    private static String partExpr(FieldMappingRule rule, Map<?, ?> part, int index, int step, int depth) {
        String transform = part.get("transform") == null ? "" : part.get("transform").toString().trim();
        if (transform.isEmpty() || TransformType.STRING.name().equals(transform)) {
            return sourceExpr(partPath(rule, part, index), step, depth);
        }
        if (TransformType.INT.name().equals(transform)) {
            return "$number(" + sourceExpr(partPath(rule, part, index), step, depth) + ")";
        }
        if (TransformType.DATE.name().equals(transform)) {
            return "$dateFormat(" + sourceExpr(partPath(rule, part, index), step, depth) + ", "
                    + jsonString(datePatternOf(part.get("datePattern"))) + ")";
        }
        if (TransformType.ENUM.name().equals(transform)) {
            return "$lookup(" + enumLiteral(enumMapOf(part)) + ", "
                    + sourceExpr(partPath(rule, part, index), step, depth) + ")";
        }
        if (TransformType.INDEX.name().equals(transform)) {
            if (depth == 0) {
                // 顶层拼接行上 `$i` 根本没绑定（编出来是 `($i-1 + 1)` 这种跑不起来的东西）。
                // 结构校验也拦这一条，消息逐字相同——服务那边把同一条合成一条
                throw new CompileException(rule.target(), null,
                        partAt(index, "行号片段只能在数组映射的子行里用") + ": " + rule.target());
            }
            // 行号片段的源就是行号本身，不带 path（startAt 默认 1）。
            // 括号不能省：这个引擎里 `&` 与 `+` **同级左结合**（实测 `"a" & 1 + 2` 解析成
            // `("a" & 1) + 2` 直接抛「+ 的左边要是数字」），不括起来整条拼接一跑到就炸。
            return "(" + indexExpr(depth, startAtOf(part.get("startAt"))) + ")";
        }
        throw new CompileException(rule.target(), null,
                partAt(index, "认不出的片段转换 " + transform) + ": " + rule.target());
    }

    /** 片段取的来源路径；没写就报清楚是哪一个片段（结构校验也有一条同样的）。 */
    private static String partPath(FieldMappingRule rule, Map<?, ?> part, int index) {
        Object path = part.get("path");
        if (path == null || path.toString().isBlank()) {
            throw new CompileException(rule.target(), null,
                    partAt(index, "缺少来源字段 path") + ": " + rule.target());
        }
        return path.toString();
    }

    /** 问题定位到哪一个片段：片段相关的消息都带它（#112 验收五）。 */
    private static String partAt(int index, String message) {
        return "拼接第 " + index + " 个片段：" + message;
    }

    /** 片段里那个格式串（与行级同一条口径：没写就用默认值）。 */
    private static String datePatternOf(Object pattern) {
        String text = pattern == null ? null : pattern.toString();
        return text == null || text.isBlank() ? DEFAULT_DATE_PATTERN : text;
    }

    /** 片段里的起始值（认不出来的按默认 1 算）。 */
    private static Integer startAtOf(Object startAt) {
        if (startAt instanceof Number n) {
            return n.intValue();
        }
        try {
            return startAt == null ? null : Integer.valueOf(startAt.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> enumMapOf(Map<?, ?> part) {
        Object map = part.get("enumMap");
        return map instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * 来源路径表达式。
     *
     * @param source $root=当前根；普通路径：顶层 "$s_<step>.<path>"，组内 "$g<d-1>.<path>"。
     */
    private static String sourceExpr(String source, int step, int depth) {
        if (source == null || source.isBlank()) {
            return "null";
        }
        if (source.startsWith("$root")) {
            // $root 是前缀：$root.bomNo → $s_<step>.bomNo（组内跨层级引用外层头字段）
            return "$s_" + step + source.substring("$root".length());
        }
        String var = depth == 0 ? "$s_" + step : "$g" + (depth - 1);
        return var + "." + source;
    }

    private static String indexExpr(int depth, Integer startAt) {
        // $i 为 0 基行号，目标行号 = $i + startAt（默认 1）
        int base = startAt == null ? 1 : startAt;
        return "$i" + (depth - 1) + " + " + base;
    }

    private static String enumLiteral(Map<String, Object> enumMap) {
        try {
            return MAPPER.writeValueAsString(enumMap == null ? Map.of() : enumMap);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("枚举映射无法序列化", e);
        }
    }

    private static String jsonString(String value) {
        try {
            return MAPPER.writeValueAsString(value == null ? "" : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("字符串字面量无法序列化", e);
        }
    }

    // ---------- 目标树节点 ----------

    private static final class TargetNode {
        final Map<String, TargetNode> children = new LinkedHashMap<>();
        Leaf leaf;
    }

    private record Leaf(FieldMappingRule rule, int step, int depth, boolean group) {
    }
}
