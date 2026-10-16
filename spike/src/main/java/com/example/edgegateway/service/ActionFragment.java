package com.example.edgegateway.service;

import com.example.edgegateway.config.action.ActionConfig;
import com.example.edgegateway.config.action.FieldMappingConfig;
import com.example.edgegateway.config.action.HttpCallConfig;
import com.example.edgegateway.config.action.MqPublishConfig;
import com.example.edgegateway.config.action.WriteUpsertConfig;
import com.example.edgegateway.config.datasource.DataSourceConfig;
import com.example.edgegateway.config.datasource.HttpDataSourceConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.service.sql.SqlDialect;
import org.apache.camel.Exchange;
import org.apache.camel.model.ProcessorDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 「一种动作一段片段」的分派（#145 从 {@code RouteGenerator} 搬出）：链上逐条走的那几种动作
 * （FIELD_MAP / WRITE_UPSERT / HTTP_CALL / MQ_PUBLISH / READ_SQL / MQ_CONSUME）各自往链上追加自己那一段，
 * 每种动作记一条成功消息步，支持断点续跑时跳过。
 *
 * <p>基数变化那三种不在这里：拆分 / 合并 / 还原各自要开一层扇出、还要按层数记账，分别归
 * {@link SplitFragment} / {@link AggregateFragment} / {@link RestoreFragment}；装配处按动作种类分派，
 * 走不到这里（那个 switch 里 {@code ONE_TO_MANY} / {@code AGGREGATE} 两档原样返回）。</p>
 *
 * <p>构建期解析（URL / 连接池 / 方言）都放在片段之外先算好——路由建一次、消息执行很多次，
 * 这些值不应每条消息重算；解析失败也在**建路由**时就抛出，由启用闸口拦截。</p>
 */
@Component
class ActionFragment {

    private final StepLedger ledger;
    private final AggregateFragment aggregateFragment;
    private final ReadWriteFragments readWriteFragments;
    private final DataSourceMapper dataSourceMapper;

    ActionFragment(StepLedger ledger, AggregateFragment aggregateFragment,
                   ReadWriteFragments readWriteFragments, DataSourceMapper dataSourceMapper) {
        this.ledger = ledger;
        this.aggregateFragment = aggregateFragment;
        this.readWriteFragments = readWriteFragments;
        this.dataSourceMapper = dataSourceMapper;
    }

    /** 单个动作的片段；每个片段记录一条消息步（成功），支持断点续跑时跳过。 */
    ProcessorDefinition<?> append(ProcessorDefinition<?> current, Action action, ActionConfig config) {
        int index = action.getActionIndex();
        return switch (action.getType()) {
            case FIELD_MAP -> {
                FieldMappingConfig mapping = (FieldMappingConfig) config;
                // DS-10.2 S1：结构化规则编译为 JSONata 表达式（路由构建时编译一次），执行器跨消息复用
                JsonataFieldMapper fieldMapper = "EXPRESSION".equals(mapping.mode())
                        ? JsonataFieldMapper.fromExpression(mapping.expression())
                        : new JsonataFieldMapper(mapping.rules());
                yield current.process(ex -> {
                    if (ledger.isResumeSkipped(ex, index)) {
                        return;
                    }
                    ledger.beginStep(ex); // #134：计时起点打在动作执行之前
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    Object input = ex.getMessage().getBody();
                    ex.setProperty(MessageHistoryService.STEP_INPUT, input);
                    Map<Integer, Object> context = StepLedger.context(ex);
                    ex.getMessage().setBody(fieldMapper.apply(context));
                    context.put(index, ex.getMessage().getBody());
                    ex.setProperty(RouteKeys.CONTEXT, context);
                    ledger.recordSuccessStep(ex, action, input);
                });
            }
            case WRITE_UPSERT -> {
                WriteUpsertConfig write = (WriteUpsertConfig) config;
                JdbcTemplate writeJdbc = new JdbcTemplate(readWriteFragments.customerDataSource(write.dataSourceId()));
                // 方言在构建期定一次（不是每条消息重算）：库类型决定 upsert 写法（ADR-0014）
                SqlDialect writeDialect = readWriteFragments.requireDialect(write.dataSourceId());
                yield current.process(ex -> {
                    if (ledger.isResumeSkipped(ex, index)) {
                        return;
                    }
                    ledger.beginStep(ex); // #134：计时起点打在动作执行之前
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    Object input = ex.getMessage().getBody();
                    ex.setProperty(MessageHistoryService.STEP_INPUT, input);
                    readWriteFragments.upsert(ex, writeJdbc, write.tableName(), write.idempotencyKey(), writeDialect);
                    ledger.recordSuccessStep(ex, action, input);
                });
            }
            case HTTP_CALL -> {
                HttpCallConfig call = (HttpCallConfig) config;
                DataSource ds = dataSourceMapper.selectById(call.dataSourceId());
                HttpDataSourceConfig http = (HttpDataSourceConfig)
                        DataSourceConfig.from(ds.getType(), ds.getAddition());
                String url = readWriteFragments.composeUrl(ds.getAddress(), call.path());
                String method = call.method() == null || call.method().isBlank() ? "POST" : call.method();
                String auth = http.token() == null || http.token().isBlank() ? null : "Bearer " + http.token();
                yield current
                        .process(ex -> {
                            if (ledger.isResumeSkipped(ex, index)) {
                                ex.setProperty(RouteKeys.SKIP_FLAG, true);
                                return;
                            }
                            ex.setProperty(RouteKeys.SKIP_FLAG, false);
                            ledger.beginStep(ex); // #134：计时起点打在动作执行之前（含出站与响应捕获）
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
                            // 出站前先把 context[0] 落在**请求体**上：StepLedger.context 是懒建的（拿当时的
                            // body 当任务输入），等出站后再建就把响应当成了任务输入。链上已有 context 时这里是空操作（#123）
                            StepLedger.context(ex);
                            ex.getMessage().setHeader(Exchange.HTTP_METHOD, method);
                            ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                            if (auth != null) {
                                ex.getMessage().setHeader("Authorization", auth);
                            }
                        })
                        .choice()
                            .when(ex -> !Boolean.TRUE.equals(ex.getProperty(RouteKeys.SKIP_FLAG, Boolean.class)))
                                .marshal().json()
                                .to(url)
                                .process(ex -> readWriteFragments.captureResponse(ex, action, index, call, method, url))
                        .endChoice()
                        .end();
            }
            case MQ_PUBLISH -> {
                MqPublishConfig publish = (MqPublishConfig) config;
                String exchange = publish.exchange();
                String routingKey = publish.routingKey() == null
                        ? (publish.queue() == null ? "" : publish.queue())
                        : publish.routingKey();
                yield current
                        .process(ex -> {
                            if (ledger.isResumeSkipped(ex, index)) {
                                ex.setProperty(RouteKeys.SKIP_FLAG, true);
                                return;
                            }
                            ex.setProperty(RouteKeys.SKIP_FLAG, false);
                            ledger.beginStep(ex); // #134：计时起点打在动作执行之前（含序列化与投递）
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
                            // bodySourceStep 必填（MqPublishConfig.validate）：显式声明发布哪一步的输出
                            Map<Integer, Object> context = StepLedger.context(ex);
                            Integer step = publish.bodySourceStep();
                            if (step == null) {
                                throw new IllegalStateException(
                                        "MQ 发布动作缺少 bodySourceStep，请先补配（如 FIELD_MAP 的序号）");
                            }
                            Object source = context.get(step);
                            if (source == null) {
                                // 显式失败而非静默：断点重投跳过来源步骤时绝不发旧 body
                                throw new IllegalStateException(
                                        "bodySourceStep=" + step + " 无输出（步骤未执行或被断点跳过），已中止发布");
                            }
                            ex.getMessage().setBody(source);
                        })
                        .choice()
                            .when(ex -> !Boolean.TRUE.equals(ex.getProperty(RouteKeys.SKIP_FLAG, Boolean.class)))
                                .marshal().json()
                                .to("spring-rabbitmq:" + exchange + "?routingKey=" + routingKey + "&disableReplyTo=true")
                                .process(ex -> ledger.recordSuccessStep(ex, action,
                                        ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class)))
                        .endChoice()
                        .end();
            }
            case READ_SQL -> current.process(ex -> {
                ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                StepLedger.putContext(ex, index, ex.getMessage().getBody());
            });
            case MQ_CONSUME -> current.process(ex -> {
                ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                StepLedger.putContext(ex, index, ex.getMessage().getBody());
            });
            // 一分多 / 合并这两档的片段整个在 appendSplit / appendRestore / appendAggregate* 里，
            // 这里没有可加的
            case ONE_TO_MANY, AGGREGATE -> current;
        };
    }
}
