package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.config.action.ActionConfig;
import com.example.edgegateway.config.action.AggregateConfig;
import com.example.edgegateway.config.action.MqConsumeConfig;
import com.example.edgegateway.config.action.OneToManyConfig;
import com.example.edgegateway.config.action.ReadSqlConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.Job;
import org.apache.camel.Exchange;
import org.apache.camel.builder.ExpressionBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.LoopDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 路由生成器（DS-8 片段组合）：**只做装配**——判定入口、按序号把片段串成一条路由、按开过的层数收尾。
 * 入口：链首为 MQ_CONSUME → 消费入口（MQ 消费主路由 + direct 重放路由）；否则直连入口（direct；
 * 自动触发由 JobScheduler 投递）。错误处理统一：onException（重试/退避）+ 消息步 FAILED + 运行 FAILED
 * 标记；每个任务额外生成"断点续跑路由"（direct:job-&lt;id&gt;-resume），供失败消息步精确重投。
 *
 * <p><b>#145 拆分后的分工</b>（纯搬移 + 改调用，行为零变化；逐个方法去了哪个类见那个提交的对照表）：</p>
 * <ul>
 *   <li>本类——装配：{@link #build}、三种入口与断点续跑路由、{@link #appendChain}；</li>
 *   <li>{@link StepLedger}——消息步 / 运行簿记；{@link ActionFragment}——「一种动作一段片段」；</li>
 *   <li>{@link SplitFragment} / {@link AggregateFragment} / {@link RestoreFragment}——基数变化三段；</li>
 *   <li>{@link ReadWriteFragments}——读库分批 / 幂等写 / 响应捕获；</li>
 *   <li>{@link ErrorHandling}——全链失败面；</li>
 *   <li>{@link RouteKeys}——三边共用的 {@code edge.*} 运行期属性名。</li>
 * </ul>
 * <p>这里的层数记账（{@code openScopes}）是**装配**的事：每开一层就要由内到外 {@code end()} 一次，
 * 而 Camel 的 {@code end()} 在循环体内只往外退一层，所以要按开过的层数退够。片段类不管这件事。</p>
 */
@Component
public class RouteGenerator {

    private final DataSourceMapper dataSourceMapper;
    private final WatermarkStore watermarkStore;
    private final MessageHistoryService messageHistory;
    private final DataSourceManager dataSourceManager;
    private final ReadSqlBatcher readSqlBatcher;
    /** 只往下传给 {@link StepLedger}（#146 的运行标识回填）；路由本身不判互斥。 */
    private final JobRunGuard runGuard;

    private final StepLedger ledger;
    private SplitFragment splitFragment;
    private AggregateFragment aggregateFragment;
    private RestoreFragment restoreFragment;
    private ActionFragment actionFragment;
    private ReadWriteFragments readWriteFragments;

    /**
     * 生产装配（Spring）：片段与簿记都由容器注入，本类只把它们按链上的顺序串起来。
     * {@link AggregateFragment} 与 {@link RestoreFragment} 共用 {@link StepLedger} 那一份实例。
     *
     * <p>这个注解不是装饰：本类还留着一个 5 参数的便捷构造器（给不跑 Spring 的单测用），两个构造器
     * 摆在 Spring 面前会有歧义——显式点名生产装配那一个，别让它去猜。</p>
     */
    @Autowired
    public RouteGenerator(
            DataSourceMapper dataSourceMapper,
            WatermarkStore watermarkStore,
            MessageHistoryService messageHistory,
            DataSourceManager dataSourceManager,
            ReadSqlBatcher readSqlBatcher,
            StepLedger ledger,
            JobRunGuard runGuard,
            SplitFragment splitFragment,
            AggregateFragment aggregateFragment,
            RestoreFragment restoreFragment,
            ActionFragment actionFragment,
            ReadWriteFragments readWriteFragments) {
        this.dataSourceMapper = dataSourceMapper;
        this.watermarkStore = watermarkStore;
        this.messageHistory = messageHistory;
        this.dataSourceManager = dataSourceManager;
        this.readSqlBatcher = readSqlBatcher;
        this.ledger = ledger;
        this.runGuard = runGuard;
        this.splitFragment = splitFragment;
        this.aggregateFragment = aggregateFragment;
        this.restoreFragment = restoreFragment;
        this.actionFragment = actionFragment;
        this.readWriteFragments = readWriteFragments;
    }

    /**
     * 便捷入口（#145 之前那一个构造器）：自个儿把片段与簿记接起来，只给「不跑 Spring 的单测/探针」用。
     *
     * <p>生产装配一律走上面那个构造器（片段由 Spring 注入）——那边一份片段被所有任务共用，
     * 这边每 new 一次就是一套自己的片段（攒批表也跟着自己一份，别拿它当单例用）。</p>
     */
    RouteGenerator(
            DataSourceMapper dataSourceMapper,
            WatermarkStore watermarkStore,
            MessageHistoryService messageHistory,
            DataSourceManager dataSourceManager,
            ReadSqlBatcher readSqlBatcher) {
        this(dataSourceMapper, watermarkStore, messageHistory, dataSourceManager, readSqlBatcher,
                new StepLedger(messageHistory, null),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public List<RouteBuilder> build(Job job, List<Action> actions) {
        List<Action> sorted = actions.stream()
                .sorted(Comparator.comparing(Action::getActionIndex))
                .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("任务没有动作，无法生成路由");
        }
        if (splitFragment == null) {
            wireFragments();
        }
        List<RouteBuilder> builders = new ArrayList<>();
        if (sorted.getFirst().getType() == ActionKind.MQ_CONSUME) {
            builders.add(buildConsumerEntry(job, sorted));
            builders.add(buildReplay(job, sorted));
        } else {
            builders.add(buildPollingEntry(job, sorted));
        }
        builders.add(buildResumeRoute(job, sorted));
        return builders;
    }

    /**
     * 便捷构造器那条路上片段还没接：这里按依赖顺序接一次（只会发生一次——接完 {@code splitFragment}
     * 就不再是 {@code null} 了）。
     */
    private void wireFragments() {
        aggregateFragment = new AggregateFragment(ledger, messageHistory);
        splitFragment = new SplitFragment(ledger);
        restoreFragment = new RestoreFragment(ledger, messageHistory);
        readWriteFragments = new ReadWriteFragments(ledger, dataSourceManager, readSqlBatcher,
                watermarkStore, messageHistory);
        actionFragment = new ActionFragment(ledger, aggregateFragment, readWriteFragments, dataSourceMapper);
    }

    /**
     * 运行收尾（**执行顺序与拆分前一致**）：先清理该运行仍挂着的**攒批**（#142 的未完成批次兜底），再写入运行状态。
     *
     * <p>清理放在写库**之前**：写库那一句抛出异常时（数据库不可用），攒批已经清理过，不会有未清理的批次
     * 残留在实例上。清理只在此处进行一次——攒批表以 {@link AggregateFragment} 为唯一写入方；若两处都能
     * 清理，就会出现「先清理者清掉了后清理者正要用的数据」这类时序依赖缺陷。</p>
     */
    private void finishRun(Exchange ex, String status, String error) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        aggregateFragment.clearPendingBatches(runId);
        ledger.finishRun(ex, status, error);
    }

    /** 把这一步的输出写回上下文（{@code context[index] = value}）——入口那几处"记一步"用。 */
    private static void putContext(Exchange ex, int index, Object value) {
        StepLedger.putContext(ex, index, value);
    }

    /**
     * MySQL 方言的幂等 upsert SQL（#67）：**同包历史单测的入口**，实现整个在
     * {@link ReadWriteFragments#buildUpsertSql(Map, String, String)}。
     *
     * <p>#145 拆分时把这个入口留在原地（测试断言的调用点不动），生产路径一律显式传方言——见
     * {@link ReadWriteFragments#upsert}。</p>
     */
    static String buildUpsertSql(Map<String, Object> row, String table, String idempotencyKey) {
        return ReadWriteFragments.buildUpsertSql(row, table, idempotencyKey);
    }

    /** 直连入口：仅构建 direct（手动/外部/自动调度共用一个执行入口）；自动触发由 JobScheduler 依调度配置投递。 */
    private RouteBuilder buildPollingEntry(Job job, List<Action> actions) {
        String routeId = "job-" + job.getId();
        return new RouteBuilder() {
            @Override
            public void configure() {
                ErrorHandling.configure(this, job, false, ledger);
                RouteDefinition route = from("direct:" + routeId).routeId(routeId);
                route.process(ex -> ledger.beginRun(ex, job));
                ProcessorDefinition<?> chain = appendChain(route, actions, job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /**
     * 消费入口：MQ 消费主路由（MQ_CONSUME 为入口）。exchange 名/类型/binding key 取动作侧配置（ADR-0010），
     * 缺省回退：exchange=数据源名、exchangeType=direct、routingKey=queue（存量任务零影响）。
     */
    private RouteBuilder buildConsumerEntry(Job job, List<Action> actions) {
        String routeId = "job-" + job.getId();
        MqConsumeConfig consume = (MqConsumeConfig) ActionConfig.from(
                ActionKind.MQ_CONSUME, actions.getFirst().getAddition());
        DataSource mqDs = dataSourceMapper.selectById(consume.dataSourceId());
        String endpoint = "spring-rabbitmq:" + consume.effectiveExchange(mqDs.getName())
                + "?exchangeType=" + consume.effectiveExchangeType()
                + "&queues=" + consume.queue()
                + "&routingKey=" + consume.effectiveRoutingKey(consume.queue())
                + "&autoDeclare=true";
        return new RouteBuilder() {
            @Override
            public void configure() {
                ErrorHandling.configure(this, job, true, ledger);
                ProcessorDefinition<?> route = from(endpoint)
                        .routeId(routeId)
                        .unmarshal().json()
                        .process(ex -> {
                            putContext(ex, actions.getFirst().getActionIndex(), ex.getMessage().getBody());
                            ledger.beginRun(ex, job);
                            ledger.beginMessage(ex, consume.businessKey());
                        });
                ProcessorDefinition<?> chain = appendChain(route, actions.subList(1, actions.size()), job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /** 重放路由：direct 入口接收已解析输入（手动执行/断点重放）。 */
    private RouteBuilder buildReplay(Job job, List<Action> actions) {
        MqConsumeConfig consume = (MqConsumeConfig) ActionConfig.from(
                ActionKind.MQ_CONSUME, actions.getFirst().getAddition());
        return new RouteBuilder() {
            @Override
            public void configure() {
                ErrorHandling.configure(this, job, false, ledger);
                RouteDefinition route = from("direct:job-" + job.getId())
                        .routeId("direct-" + job.getId())
                        .process(ex -> {
                            putContext(ex, actions.getFirst().getActionIndex(), ex.getMessage().getBody());
                            ledger.beginRun(ex, job);
                            ledger.beginMessage(ex, consume.businessKey());
                        });
                ProcessorDefinition<?> chain = appendChain(route, actions.subList(1, actions.size()), job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /**
     * 断点续跑路由：direct 入口接收失败步数据，从 {@code edge.resumeFromStep} 动作起重放。
     *
     * <p>重投入口随 exchange 带上 {@code edge.resumeContext}（同一消息更早成功步骤的 data_snapshot，#90），
     * 在此回填 context：被跳过的上游步骤因此仍可被下游规则引用（如 FIELD_MAP 的 {@code sourceStep}），
     * 不再静默映射出空。{@code context[0]} 保持既有语义——本次重投的 body（该步 inputSnapshot）。</p>
     */
    private RouteBuilder buildResumeRoute(Job job, List<Action> actions) {
        String routeId = "resume-" + job.getId();
        return new RouteBuilder() {
            @Override
            public void configure() {
                ErrorHandling.configure(this, job, false, ledger);
                RouteDefinition route = from("direct:job-" + job.getId() + "-resume")
                        .routeId(routeId)
                        .process(ex -> {
                            // StepLedger.context(ex) 已把 context[0] 置为本次重投的 body
                            //（= 失败步 inputSnapshot），此处只回填上游步骤
                            Map<Integer, Object> next = StepLedger.context(ex);
                            Object upstreamContext = ex.getProperty(MessageHistoryService.RESUME_CONTEXT, Object.class);
                            if (upstreamContext instanceof Map<?, ?> upstream) {
                                upstream.forEach((step, data) -> {
                                    // 只回填动作序号 > 0 的步骤；0 号槽位是本次重投的输入，不覆盖
                                    if (step instanceof Integer index && index > 0) {
                                        next.put(index, data);
                                    }
                                });
                            }
                            ex.setProperty(RouteKeys.CONTEXT, next);
                            // Camel 的 split/choice 片段之间的 body 约定：链上"当前数据"就是 context[0]
                            ex.getMessage().setBody(next.get(0));
                            ledger.beginRun(ex, job);
                            ledger.beginMessage(ex, null); // 业务键已由重投入口写入，直接沿用
                        });
                ProcessorDefinition<?> chain = appendChain(route, actions.subList(1, actions.size()), job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /** 按动作种类逐段追加片段；READ_SQL 触发 split，其后的片段在行内执行。返回追加后的链节点。 */
    private ProcessorDefinition<?> appendChain(ProcessorDefinition<?> route, List<Action> actions, Job job) {
        ProcessorDefinition<?> current = route;
        int openScopes = 0;
        boolean inSplit = false;
        boolean inBatchLoop = false;
        for (Action action : actions) {
            ActionKind kind = action.getType();
            ActionConfig config = ActionConfig.from(kind, action.getAddition());
            if (kind == ActionKind.READ_SQL && !inSplit) {
                ReadSqlConfig read = (ReadSqlConfig) config;
                // 确保动作选定的客户库连接池已建立，路由启动才能按名解析/构造 JdbcTemplate
                readWriteFragments.customerDataSource(read.dataSourceId());
                boolean hasWatermark = read.watermarkColumn() != null && !read.watermarkColumn().isBlank();
                if (hasWatermark) {
                    // #79 分批 + keyset 分页：读水位 → 循环（读一批 → split 行 → 整批成功才推进水位）→ 直到扫完
                    LoopDefinition loop = current
                            .process(ex -> readWriteFragments.prepareRead(ex, job, read, action.getActionIndex(), action.getName()))
                            .loopDoWhile(readWriteFragments::moreBatches);
                    current = loop.process(ex -> readWriteFragments.readBatch(ex, job, read))
                            .split(ExpressionBuilder.bodyExpression());
                    inBatchLoop = true;
                } else {
                    // 无水位列：全量单次读（不分批、不推进水位），保留原语义
                    String sql = read.sql();
                    current = current
                            .process(ex -> {
                                ex.setProperty(MessageHistoryService.ACTION_INDEX, action.getActionIndex());
                                ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                                // 不分批那条路：一次读 = 一批，所以批号打在这里（#139）
                                ex.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
                                ledger.beginStep(ex); // #134：计时起点打在读库之前
                            })
                            .to("sql:" + sql + "?dataSource=#edge-ds-" + read.dataSourceId() + "&outputType=SelectList")
                            // 读完了就结算（#134）：落库发生在每条源行上，而读只做了一次。
                            // 在 split 之前结算，各源行共用这一个数；不然第二条源行量到的是「读库 + 前一条跑完」
                            .process(ledger::settleStepDuration)
                            .split(ExpressionBuilder.bodyExpression());
                }
                openScopes++;
                inSplit = true;
                // 源行为"消息创建"时机：每条源行 = 一条消息，在 split 内首部创建消息标识与业务键
                current = current.process(ex -> ledger.beginMessage(ex, read.businessKey()));
                // 读取动作本身也作为链上一步：每条源行记一条 SUCCESS 消息步（无输入快照，data=该行）
                current = current.process(ex -> ledger.recordSuccessStep(ex, action, null));
            }
            if (kind == ActionKind.ONE_TO_MANY) {
                // 一分多（1 → N）两档：
                //   拆分档——造新消息（新标识、换批号、元素按顺序摊开）；
                //   还原档——还原成员（身份从成员清单里来、不换批号、按业务键配对）。
                // 两档都要开一层扇出：end() 由收尾那段按层数完成，其后的动作片段照旧逐条追加。
                OneToManyConfig oneToMany = (OneToManyConfig) config;
                current = oneToMany.isRestore()
                        ? restoreFragment.appendRestore(current, action, oneToMany)
                        : splitFragment.appendSplit(current, action, oneToMany);
                openScopes++;
            }
            if (kind == ActionKind.AGGREGATE) {
                // 合并（#142 / ADR-0017 决策 6 的修订版）分两半，位置由「攒齐的边界 = 上游读取批读完那一刻」定：
                //  ① 前半段留在**当前这一层**（读库/拆分那几层扇出之内）——每条消息各来一次，记一张承运单；
                //  ② 然后把扇出那几层收回来（= 这一批读完了），在**批 exchange** 上打成一条报文。
                // 打完**不开新扇出**：链上从这里起只有一条消息，后面的出站动作（HTTP_CALL / MQ_PUBLISH）
                // 天然只发一次；响应由还原（RESTORE）那一档摊回原来那 N 条。
                //
                // 空批（读库读到 0 行 / 整批被续投跳过）用一层 filter 拦截：否则**每个「没有新数据」的运行**
                // 都会朝目标端发一份空报文。这一层与还原那层一样按 openScopes 记账、收尾时统一退回来。
                current = aggregateFragment.appendAggregateCollect(current, action);
                while (openScopes > 0) {
                    current = current.end();
                    openScopes--;
                }
                current = aggregateFragment.appendAggregatePack(current, action, (AggregateConfig) config);
                current = current.filter(ex -> !Boolean.TRUE.equals(ex.getProperty(RouteKeys.BATCH_EMPTY, Boolean.class)));
                openScopes = 1;
            }
            current = actionFragment.append(current, action, config);
        }
        // 收尾按**嵌套的由内到外**顺序关闭。一条链上可能叠着多层：读库分批那层（loopDoWhile + split）、
        // 拆分那层（split）、合并之后那层（filter，空批兜底）与还原那层（split）。
        //
        // 两点注意事项：① 每开一层就要 end() 一次，而且必须**由内到外**（先关还原/拆分那些、再交水位、
        // 最后关循环），否则水位处理器会被追加到内层里、每行提交一次；② Camel 的 `end()` 在**循环体内**
        // 只往外退一层，嵌套时要按开过的层数退够（所以这里调 ProcessorDefinition.end()，不假设是哪一种定义）。
        while (openScopes > 0) {
            current = current.end();
            openScopes--;
        }
        if (inBatchLoop) {
            // 一批的行处理完（含其中每条子消息走完拆分之后的步骤），才提交该批水位；
            // 批失败时不会走到这一步，从而实现"批失败只丢该批"
            current = current.process(ex -> readWriteFragments.updateWatermark(ex, job));
            current = ((LoopDefinition) current).end();
        }
        return current;
    }
}
