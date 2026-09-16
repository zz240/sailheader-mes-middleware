package com.example.edgegateway.service;

import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.FieldMappingRule;
import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.web.dto.MappingTrialRequest;
import com.example.edgegateway.web.dto.MappingTrialResult;
import com.example.edgegateway.web.dto.MappingTrialResult.Failure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「试算」：把编辑器里那份草稿拿到一份来源样例上**真跑一遍**，看目标报文长什么样（#116）。
 *
 * <p>它只读、无状态、不落库：草稿与样例都在请求里，配置格式一个字不改（#116 验收八）。</p>
 *
 * <p>跑法两段式（ADR-0012 决策 6）：</p>
 * <ol>
 *   <li><b>先整份跑一次</b>——与真实执行走同一条路：{@link JsonataFieldMapper} 编译一次、把编译产物
 *       跑在这份数据上（规则树或手写表达式，按草稿的 {@code mode} 分岔）。跑通了就把目标报文原样给回去。
 *       这是**真话**那一次。</li>
 *   <li><b>抛错时再逐行跑一遍做归因</b>——单独跑某一行（数组子行连同它的外层组一起包回去，好让
 *       {@code $g0} 这类变量仍有绑定），哪一行单独跑也失败就报哪一行。这是**定位手段**：
 *       整份没跑通与「这一行自己有问题」不是一回事，界面要写明。</li>
 * </ol>
 *
 * <p>求值带引擎自带的资源上限（{@link com.example.edgegateway.mapping.JsonataLimits}）：写坏的表达式
 * 到点就停，不会把请求线程一直占着——运行时那边同一份上限，见 {@link JsonataFieldMapper}。</p>
 */
@Service
public class MappingTrialService {

    /** 手写表达式那一边没有「行」可归因（那边只有一整段表达式）。 */
    private static final String EXPRESSION_MODE = "EXPRESSION";

    private final ObjectMapper objectMapper;

    public MappingTrialService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 试算一份草稿：整份跑一次，抛错时逐行归因。 */
    public MappingTrialResult trial(MappingTrialRequest request) {
        FieldMappingConfig config;
        try {
            config = FieldMappingConfig.from(request.draft() == null ? Map.of() : request.draft());
        } catch (RuntimeException e) {
            // 草稿连配置都读不出来（未知字段、认不出的转换名…）：跑不了，说清楚为什么
            return failed("这份草稿没法读成字段映射配置：" + e.getMessage());
        }
        Object sample;
        try {
            sample = sampleOf(request);
        } catch (IllegalArgumentException e) {
            return failed(e.getMessage());
        }
        Map<Integer, Object> context = new LinkedHashMap<>();
        context.put(request.sampleStep() == null ? 0 : request.sampleStep(), sample);

        JsonataFieldMapper mapper;
        try {
            mapper = mapperOf(config);
        } catch (RuntimeException e) {
            // 编译不过（语法错、结构错、表达式空着）：整份跑不起来，谈不上逐行
            return failed(messageOf(e));
        }
        try {
            return new MappingTrialResult(true, mapper.apply(context), null, List.of());
        } catch (Exception e) {
            return new MappingTrialResult(false, null, messageOf(e), failures(config, context));
        }
    }

    // ---------- 输入 ----------

    /** 请求里那份样例原文 → 要跑的数据；没有样例时给一句能照着做的说法（试算没数据可跑）。 */
    private Object sampleOf(MappingTrialRequest request) {
        if (request.sampleJson() == null || request.sampleJson().isBlank()) {
            throw new IllegalArgumentException(
                    "还没有可试算的来源样例——用「从运行记录探测」或「粘贴 JSON」给一份");
        }
        return MappingSampleData.parse(objectMapper, request.sampleJson()).value();
    }

    /** 按草稿的 mode 走两条并列的执行路径（与路由生成那边同一对入口，编译一次就跑）。 */
    private JsonataFieldMapper mapperOf(FieldMappingConfig config) {
        return EXPRESSION_MODE.equals(config.mode())
                ? JsonataFieldMapper.fromExpression(config.expression())
                : new JsonataFieldMapper(config.rules());
    }

    // ---------- 逐行归因 ----------

    /**
     * 整份没跑通时：逐行单独跑一遍，报出**单独跑也失败**的那些行。
     *
     * <p>手写表达式那一边没有行（那边只有一整段表达式），所以归因列表是空的——界面上就只显示整份那句错。</p>
     */
    private List<Failure> failures(FieldMappingConfig config, Map<Integer, Object> context) {
        if (EXPRESSION_MODE.equals(config.mode())) {
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
    private void attribute(FieldMappingRule rule, List<FieldMappingRule> ancestors, String parentTarget,
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

    /** 单独跑这一份：跑通了返回 null，没跑通返回引擎那句话。 */
    private static String runAlone(List<FieldMappingRule> rules, Map<Integer, Object> context) {
        try {
            new JsonataFieldMapper(rules).apply(context);
            return null;
        } catch (Exception e) {
            return messageOf(e);
        }
    }

    /** 抛出来的那句话；引擎偶尔给个没有 message 的异常，那时至少说清是哪一类。 */
    private static String messageOf(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static MappingTrialResult failed(String message) {
        return new MappingTrialResult(false, null, message, List.of());
    }
}
