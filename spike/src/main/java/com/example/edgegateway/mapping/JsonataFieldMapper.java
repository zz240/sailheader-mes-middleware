package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata;
import com.example.edgegateway.config.FieldMappingRule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段映射执行器（DS-10.2 S1）：路由生成时编译一次 rules → JSONata 表达式，
 * 消息到达时按上下文绑定来源步骤变量并求值。替代自研 FieldMapper 成为 FIELD_MAP 的执行路径。
 *
 * <p>线程安全：dashjoin jsonata 解析/求值线程安全，实例在路由构建时创建、跨消息复用；
 * DATE 自定义函数在构造时注册一次。</p>
 */
public final class JsonataFieldMapper {

    private final Jsonata expression;
    private final Set<Integer> steps;

    public JsonataFieldMapper(List<FieldMappingRule> rules) {
        String compiled = JsonataRuleCompiler.compile(rules);
        try {
            this.expression = Jsonata.jsonata(compiled);
            this.expression.registerFunction("dateFormat", JsonataFunctions.dateFormat());
        } catch (Exception e) {
            throw new IllegalArgumentException("字段映射编译失败: " + e.getMessage(), e);
        }
        this.steps = collectSteps(rules);
    }

    /** 规则树里引用的全部 sourceStep（含组内继承），执行时逐一绑定为 $s_<step> 变量。 */
    private static Set<Integer> collectSteps(List<FieldMappingRule> rules) {
        Set<Integer> steps = new LinkedHashSet<>();
        for (FieldMappingRule rule : rules) {
            steps.add(rule.sourceStep() != null ? rule.sourceStep() : 0);
            if (rule.children() != null) {
                steps.addAll(collectSteps(rule.children()));
            }
        }
        return steps;
    }

    /** 用上下文求值：空输入根 + frame 绑定 $s_<step>，返回映射结果。 */
    public Object apply(Map<Integer, Object> context) {
        Jsonata.Frame frame = expression.createFrame();
        for (Integer step : steps) {
            frame.bind("s_" + step, context.getOrDefault(step, Map.of()));
        }
        return expression.evaluate(Map.of(), frame);
    }
}
