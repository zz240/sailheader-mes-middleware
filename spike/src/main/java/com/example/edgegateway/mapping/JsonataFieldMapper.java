package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata;
import com.example.edgegateway.config.FieldMappingRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段映射执行器（DS-10.2 S1）：路由生成时编译一次 rules → JSONata 表达式，
 * 消息到达时按上下文绑定来源步骤变量并求值。替代自研 FieldMapper 成为 FIELD_MAP 的执行路径。
 *
 * <p>线程安全：dashjoin jsonata 解析/求值线程安全，实例在路由构建时创建、跨消息复用；
 * DATE 自定义函数在构造时注册一次。</p>
 */
public final class JsonataFieldMapper {

    private final Jsonata expression;

    public JsonataFieldMapper(List<FieldMappingRule> rules) {
        this(JsonataRuleCompiler.compile(rules));
    }

    /** mode=EXPRESSION：直接使用手写 JSONata 表达式（逃生通道），同样注册 DATE 自定义函数。 */
    public static JsonataFieldMapper fromExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("mode=EXPRESSION 需要 expression 表达式");
        }
        return new JsonataFieldMapper(expression);
    }

    private JsonataFieldMapper(String compiled) {
        try {
            this.expression = Jsonata.jsonata(compiled);
            this.expression.registerFunction("dateFormat", JsonataFunctions.dateFormat());
        } catch (Exception e) {
            throw new IllegalArgumentException("字段映射编译失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用上下文求值：上下文各步骤绑定为 $s_<step> 变量，上下文 Map 作为求值根
     * （规则模式表达式只引用 $s_<step>；EXPRESSION 模式可用 $ / $$ 引用根）。
     */
    public Object apply(Map<Integer, Object> context) {
        Jsonata.Frame frame = expression.createFrame();
        // READ_SQL 行可能含 LocalDateTime/Timestamp 等非 JSON 类型，先规范化再求值
        Map<Integer, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> entry : context.entrySet()) {
            Object normalized = JsonataInputs.normalize(entry.getValue());
            safe.put(entry.getKey(), normalized);
            frame.bind("s_" + entry.getKey(), normalized);
        }
        return expression.evaluate(safe, frame);
    }
}
