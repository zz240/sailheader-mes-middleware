package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Timebox;
import com.example.edgegateway.config.FieldMappingRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段映射执行器（DS-10.2 S1）：路由生成时编译一次 rules → JSONata 表达式，
 * 消息到达时按上下文绑定来源步骤变量并求值。替代自研 FieldMapper 成为 FIELD_MAP 的执行路径。
 *
 * <p>线程安全：dashjoin jsonata 解析/求值线程安全，实例在路由构建时创建、跨消息复用；
 * DATE 自定义函数在构造时注册一次。**资源上限**（{@link JsonataLimits}）挂在每次求值新建的那份
 * 求值帧上，所以是「每一次求值各有一套」，不因为实例被复用而互相串。</p>
 */
public final class JsonataFieldMapper {

    private final Jsonata expression;
    private final JsonataLimits limits;

    public JsonataFieldMapper(List<FieldMappingRule> rules) {
        this(rules, JsonataLimits.DEFAULT);
    }

    /**
     * 指定资源上限的那一份。
     *
     * <p>运行时与试算都走上面那个默认入口（{@link JsonataLimits#DEFAULT}）；这个口子是给测试的——
     * 把上限调到一个不可能满足的值，才能确定性地断言「这道边界真的接上了」，而不是干等 5 秒。</p>
     */
    public JsonataFieldMapper(List<FieldMappingRule> rules, JsonataLimits limits) {
        this(JsonataRuleCompiler.compile(rules), limits);
    }

    /** mode=EXPRESSION：直接使用手写 JSONata 表达式（逃生通道），同样注册 DATE 自定义函数。 */
    public static JsonataFieldMapper fromExpression(String expression) {
        return fromExpression(expression, JsonataLimits.DEFAULT);
    }

    /** 指定资源上限的手写表达式那一份（与结构化规则同一套上限；同样主要给测试用）。 */
    public static JsonataFieldMapper fromExpression(String expression, JsonataLimits limits) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("mode=EXPRESSION 需要 expression 表达式");
        }
        return new JsonataFieldMapper(expression, limits);
    }

    private JsonataFieldMapper(String compiled, JsonataLimits limits) {
        this.limits = limits;
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
     *
     * <p>求值前把 {@link JsonataLimits} 挂到这一份帧上（引擎自带的 {@code Timebox}：超时 + 递归深度）。
     * 越界时抛的是引擎的 {@code JException}（「Expression evaluation timeout」/「Stack overflow error」），
     * 与其它求值错误一样往上抛——调用方（消息处理或试算）自己决定怎么说这句话。</p>
     */
    public Object apply(Map<Integer, Object> context) {
        Jsonata.Frame frame = expression.createFrame();
        // 这两条回调挂在这份帧上（Timebox 自己注册），所以引用由帧持有——这次求值结束就一起没了
        new Timebox(frame, limits.timeoutMs(), limits.maxDepth());
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
