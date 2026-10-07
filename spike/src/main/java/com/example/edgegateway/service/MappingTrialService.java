package com.example.edgegateway.service;

import com.example.edgegateway.config.action.FieldMappingConfig;
import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.web.dto.MappingTrialRequest;
import com.example.edgegateway.web.dto.MappingTrialResult;
import com.example.edgegateway.web.dto.MappingTrialResult.Metrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「试算」：把编辑器里那份草稿拿到一份来源样例上**真跑一遍**，看目标报文长什么样（#116），
 * 并逐个目标字段说清它是落值了、被丢掉了、算出来是 null，还是压根没配（#120）。
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
 * <p>逐字段状态表（#120）是**同一份结果上再加一层**，不是第三段跑法：它拿整份跑出来的那份报文，
 * 按目标路径问「这个键在不在」，键不在时再借用第二段那套「单独跑这一行」判是报错还是静默丢掉。
 * 三处口径（定位、原因原话、资源上限）与那两段完全一致——见 {@link MappingTrialReport}。</p>
 *
 * <p>求值带引擎自带的资源上限（{@link com.example.edgegateway.mapping.JsonataLimits}）：写坏的表达式
 * 到点就停，不会把请求线程一直占着——运行时那边同一份上限，见 {@link JsonataFieldMapper}。</p>
 */
@Service
public class MappingTrialService {

    private final ObjectMapper objectMapper;

    public MappingTrialService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 试算一份草稿：整份跑一次，抛错时逐行归因，跑通时再逐字段定一档状态。 */
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
            return failed(MappingTrialReport.messageOf(e));
        }
        try {
            Object output = mapper.apply(context);
            // 逐字段那一层要对报文，只有整份跑通的那一次才有报文可对；没跑通时它是空的（不是假装算过）
            MappingTrialReport.Report report =
                    MappingTrialReport.report(config, request.targetFields(), output, context);
            return new MappingTrialResult(true, output, null, List.of(), report.fields(), report.metrics());
        } catch (Exception e) {
            return new MappingTrialResult(false, null, MappingTrialReport.messageOf(e),
                    MappingTrialReport.failures(config, context), List.of(), Metrics.none());
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
        return config.isExpression()
                ? JsonataFieldMapper.fromExpression(config.expression())
                : new JsonataFieldMapper(config.rules());
    }

    private static MappingTrialResult failed(String message) {
        return new MappingTrialResult(false, null, message, List.of(), List.of(), Metrics.none());
    }
}
