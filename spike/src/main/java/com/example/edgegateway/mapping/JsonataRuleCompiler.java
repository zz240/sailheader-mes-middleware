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
    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";

    private JsonataRuleCompiler() {
    }

    /** 编译规则列表为 JSONata 表达式（顶层是目标对象构造器）。 */
    public static String compile(List<FieldMappingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("字段映射动作需要至少一条映射规则");
        }
        TargetNode root = new TargetNode();
        for (FieldMappingRule rule : rules) {
            insert(root, rule, rule.sourceStep() != null ? rule.sourceStep() : 0, 0);
        }
        StringBuilder sb = new StringBuilder("{\n");
        renderChildren(root, sb);
        sb.append("\n}");
        return sb.toString();
    }

    // ---------- 目标树构建 ----------

    /** 把一条规则按其 target 点号路径插入目标树；组节点用 children 递归继续。 */
    private static void insert(TargetNode parent, FieldMappingRule rule, int step, int depth) {
        String[] segments = rule.target().split("\\.");
        if (segments.length == 0 || segments[0].isBlank()) {
            throw new IllegalArgumentException("目标字段路径不合法: " + rule.target());
        }
        TargetNode node = parent;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isBlank()) {
                throw new IllegalArgumentException("目标字段路径不合法: " + rule.target());
            }
            TargetNode next = node.children.get(seg);
            if (i < segments.length - 1 && next != null && next.leaf != null && !next.leaf.group) {
                // 中间节点已有非组叶子：如先映射 "a.b" 再映射 "a.b.c"，c 会被静默丢弃
                throw new IllegalArgumentException("目标字段前缀冲突: " + rule.target());
            }
            node = next != null ? next : node.children.computeIfAbsent(seg, k -> new TargetNode());
        }
        boolean group = rule.children() != null && !rule.children().isEmpty();
        if (node.leaf != null) {
            throw new IllegalArgumentException("目标字段重复映射: " + rule.target());
        }
        if (!node.children.isEmpty() && !group) {
            throw new IllegalArgumentException("目标字段前缀冲突: " + rule.target());
        }
        node.leaf = new Leaf(rule, step, depth, group);
        if (group) {
            for (FieldMappingRule child : rule.children()) {
                insert(node, child, step, depth + 1);
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
                    + jsonString(rule.datePattern() == null ? DEFAULT_DATE_PATTERN : rule.datePattern()) + ")";
            case ENUM -> "$lookup(" + enumLiteral(rule.enumMap()) + ", " + sourceExpr(rule.source(), step, depth) + ")";
            case INDEX -> indexExpr(depth, rule.startAt());
            default -> sourceExpr(rule.source(), step, depth);
        };
    }

    /** CONCAT：片段交替拼接，字符串片段转字面量，路径片段转来源表达式。 */
    private static String concatExpr(FieldMappingRule rule, int step, int depth) {
        List<String> parts = new ArrayList<>();
        for (Object part : rule.parts()) {
            if (part instanceof String s) {
                parts.add(jsonString(s));
            } else if (part instanceof Map<?, ?> m) {
                parts.add(sourceExpr(m.get("path").toString(), step, depth));
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("CONCAT 转换需要 parts 片段: " + rule.target());
        }
        return String.join(" & ", parts);
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
