package com.example.edgegateway.service;

import com.example.edgegateway.config.action.ActionConfig;
import com.example.edgegateway.config.action.HttpCallConfig;
import com.example.edgegateway.config.action.MqPublishConfig;
import com.example.edgegateway.config.action.OneToManyConfig;
import com.example.edgegateway.config.datasource.DataSourceConfig;
import com.example.edgegateway.config.datasource.HttpDataSourceConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.Job;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 「打包重发」路由的构建（#145 从 {@code RouteGenerator} 搬出；语义见 #142 / ADR-0017 决策 7）：
 * 把**出站那一行**存下来的整份报文，按该动作自己的出口再发一次，**一次调用 = 一次请求**。
 *
 * <p>入口 {@code direct:job-<id>-batch-resend-<出站动作序号>} 由
 * {@link MessageHistoryService#redeliverBatch} 调用，带上「这一批哪几条消息、各自那一片是什么」。</p>
 *
 * <p>HTTP 有响应：照链上那条路配对，给这一批每条各记一行；MQ 没有响应：只记这一批的结果（不配对）。
 * 失败面复用既有 {@code onException}（那时消息标识已是摘要行、批号是原批，加上成员清单就能给这一批
 * 每条补记一行 FAILED）——那一面归装配处的 {@code configureErrorHandling}。</p>
 *
 * <p>为什么这些路由是**出站动作各一条**：摘要行落在出站那一步（决策 6 的修订），而出口（数据源 /
 * 路径 / 方法 / exchange）是构建期解析的、归路由。没有合并动作的链一条都不建（那种链上不存在
 * 「一整批」，重发按逐条重投走）。</p>
 */
@Component
class BatchResendRoute {

    private final StepLedger ledger;
    private final RestoreFragment restoreFragment;
    private final ReadWriteFragments readWriteFragments;

    BatchResendRoute(StepLedger ledger, RestoreFragment restoreFragment,
                     ReadWriteFragments readWriteFragments) {
        this.ledger = ledger;
        this.restoreFragment = restoreFragment;
        this.readWriteFragments = readWriteFragments;
    }

    /** 指向这个出口的**还原档**一分多动作（没有就返回 {@code null}）：重发时按它配对，出口自己不配对。 */
    static OneToManyConfig restoreAfter(List<Action> actions, int outletStep) {
        return actions.stream()
                .filter(action -> action.getType() == ActionKind.ONE_TO_MANY)
                .map(action -> OneToManyConfig.from(action.getAddition() == null ? Map.of() : action.getAddition()))
                // 一分多的两档里只有还原档与出口配对（拆分档是造新消息，不认任何出口）
                .filter(OneToManyConfig::isRestore)
                .filter(restore -> Integer.valueOf(outletStep).equals(restore.sourceStep()))
                .findFirst()
                .orElse(null);
    }

    /** 给一个出站动作建它那条打包重发路由。 */
    RouteBuilder build(Job job, Action action, ActionConfig config, OneToManyConfig restore,
                       DataSourceMapper dataSourceMapper) {
        int index = action.getActionIndex();
        boolean http = config instanceof HttpCallConfig;
        String uri;
        String method = "POST";
        String auth = null;
        if (config instanceof HttpCallConfig call) {
            DataSource ds = dataSourceMapper.selectById(call.dataSourceId());
            HttpDataSourceConfig httpConfig = (HttpDataSourceConfig)
                    DataSourceConfig.from(ds.getType(), ds.getAddition());
            uri = readWriteFragments.composeUrl(ds.getAddress(), call.path());
            method = call.method() == null || call.method().isBlank() ? "POST" : call.method();
            auth = httpConfig.token() == null || httpConfig.token().isBlank() ? null : "Bearer " + httpConfig.token();
        } else {
            MqPublishConfig publish = (MqPublishConfig) config;
            String exchange = publish.exchange();
            String routingKey = publish.routingKey() == null
                    ? (publish.queue() == null ? "" : publish.queue())
                    : publish.routingKey();
            uri = "spring-rabbitmq:" + exchange + "?routingKey=" + routingKey + "&disableReplyTo=true";
        }
        String target = uri;
        String httpMethod = method;
        String authorization = auth;
        return new RouteBuilder() {
            @Override
            public void configure() {
                // 失败面与链上那条路同一份：onException + 记账 + 运行标 FAILED
                configureErrorHandling(this, job);
                from("direct:job-" + job.getId() + "-batch-resend-" + index)
                        .routeId("batch-resend-" + job.getId() + "-" + index)
                        .process(ex -> {
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            ledger.beginStep(ex); // #134：计时起点打在出站之前
                            if (http) {
                                ex.getMessage().setHeader(Exchange.HTTP_METHOD, httpMethod);
                                ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                                if (authorization != null) {
                                    ex.getMessage().setHeader("Authorization", authorization);
                                }
                            }
                        })
                        .marshal().json()
                        .to(target)
                        .process(ex -> restoreFragment.settleBatchResend(ex, action, restore, index));
            }
        };
    }

    /**
     * 打包重发那条路由的失败面：与链上入口同款（{@code handled=false}，异常抛回调用方）。
     *
     * <p>断言与重试口径一字不改地照搬自装配处那一个——{@code maxRetries} / 退避 / 计数回调 / 记账
     * 处理器都走 {@link StepLedger} 同一份实现。</p>
     */
    private void configureErrorHandling(RouteBuilder builder, Job job) {
        ErrorHandling.configure(builder, job, false, ledger);
    }
}
