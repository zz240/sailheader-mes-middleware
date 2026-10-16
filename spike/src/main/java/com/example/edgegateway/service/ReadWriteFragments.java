package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.config.action.HttpCallConfig;
import com.example.edgegateway.config.action.ReadSqlConfig;
import com.example.edgegateway.config.action.WriteUpsertConfig;
import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleConfig;
import com.example.edgegateway.model.WatermarkType;
import com.example.edgegateway.service.sql.SqlDialect;
import com.example.edgegateway.service.sql.SqlDialects;
import org.apache.camel.Exchange;
import org.apache.camel.model.ProcessorDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读库与写库那几种动作的片段与辅助（#145 从 {@code RouteGenerator} 搬出）：READ_SQL 的**分批读**
 * （水位 → keyset 分页 → 整批成功才推进水位）、WRITE_UPSERT 的幂等写、HTTP 出站的**响应捕获**，
 * 以及连接池 / 方言 / URL / upsert SQL 这几样构建期就定下来的东西。
 *
 * <p>它们归一处，是因为共用同一批「客户库」设施：{@code #edge-ds-<id>} 连接池、按库类型定的方言
 * （ADR-0014）、以及「拿不到就当场报错、不猜默认方言」这条口径。读库那批的水位与批号是**每批**重算的，
 * 所以那几个属性键的读写都收在这个类里（{@link RouteKeys#WATERMARK} / {@link RouteKeys#HAS_MORE}）。</p>
 */
@Component
class ReadWriteFragments {

    private final StepLedger ledger;
    private final DataSourceManager dataSourceManager;
    private final ReadSqlBatcher readSqlBatcher;
    private final WatermarkStore watermarkStore;
    private final MessageHistoryService messageHistory;

    ReadWriteFragments(StepLedger ledger, DataSourceManager dataSourceManager, ReadSqlBatcher readSqlBatcher,
                       WatermarkStore watermarkStore, MessageHistoryService messageHistory) {
        this.ledger = ledger;
        this.dataSourceManager = dataSourceManager;
        this.readSqlBatcher = readSqlBatcher;
        this.watermarkStore = watermarkStore;
        this.messageHistory = messageHistory;
    }

    // ---- READ_SQL：分批读 + 水位 ----

    /**
     * 读水位（进入循环前）：从缓存库取上次位置，作为本批次的下界（epoch 毫秒 / 自增值）。
     *
     * <p>这里也先打一个批号：读库在**读第一批之前**就失败时（水位库连不上、SQL 语法错），一条源行都没
     * 产生，失败步要用它当占位消息标识（{@code StepLedger#recordFailure}）。真正的批号由
     * {@link #readBatch} 每批现给一个——这个只作兜底，不指望它分组（ADR-0017 决策 3）。</p>
     */
    void prepareRead(Exchange exchange, Job job, ReadSqlConfig read, int actionIndex, String actionName) {
        exchange.setProperty(MessageHistoryService.ACTION_INDEX, actionIndex);
        exchange.setProperty(MessageHistoryService.ACTION_NAME, actionName);
        exchange.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
        long value = watermarkStore.get("job:" + job.getId());
        exchange.setProperty(RouteKeys.WATERMARK, value);
        // 让首批至少执行一次（do-while 语义下无副作用；while 语义下需要先入循环）
        exchange.setProperty(RouteKeys.HAS_MORE, true);
    }

    /**
     * 读一批：keyset 分页（谓词 + ORDER BY 列 + LIMIT batchSize），计算批内水位并标记是否还有下一批。
     *
     * <p>**读库每读一批一个新批号**（#139）：批号打在进入本批 split 之前，于是这一批的每条源行
     * 共用同一个号（split 会把 exchange 属性拷给子 exchange）。一批一个号而不是一次运行一个号，
     * 是「哪几条是同一批」在库里答得出来的前提（ADR-0017 决策 2）。</p>
     */
    void readBatch(Exchange exchange, Job job, ReadSqlConfig read) {
        exchange.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
        // #134：起点与结算都打在**这一批**上（prepareRead 在循环外只跑一次，起点放那儿的话
        // 第二批起就量的是第一批那个数）。读库失败时起点已经在，异常回调照样结算得出来。
        ledger.beginStep(exchange);
        long current = exchange.getProperty(RouteKeys.WATERMARK, Long.class);
        javax.sql.DataSource ds = customerDataSource(read.dataSourceId());
        Object bound = readSqlBatcher.parseType(read) == WatermarkType.TIMESTAMP
                ? readSqlBatcher.formatTimeBound(current, ScheduleConfig.from(job).zone())
                : current;
        String sql = readSqlBatcher.batchSql(read, requireDialect(read.dataSourceId()));
        // SQL 里没有 `{watermark}`（调 batchSql 时没法把谓词塞进去，比如源表没有可用的水位列、
        // 或者查询本身是聚合的）时就没有参数可绑：照旧传 bound 会报「Parameter index out of range」。
        // 有占位符才有参数，这一格跟着 SQL 走，不跟着配置走。
        List<Map<String, Object>> rows = sql.contains("?")
                ? new JdbcTemplate(ds).queryForList(sql, bound)
                : new JdbcTemplate(ds).queryForList(sql);
        long newWatermark = readSqlBatcher.newBatchWatermark(read, rows, current, ScheduleConfig.from(job).zone());
        exchange.getMessage().setBody(rows);
        exchange.setProperty(RouteKeys.WATERMARK, newWatermark);
        exchange.setProperty(RouteKeys.HAS_MORE, readSqlBatcher.hasMore(read, rows));
        // 读完了就结算（#134）：与不分批那条路同一口径——落库在每条源行上，在 split 之前结算，
        // 各源行共用**这一次读**的耗时
        ledger.settleStepDuration(exchange);
    }

    /**
     * 整批处理成功后才推进水位（持久化）；批失败时不执行，实现"批失败只丢该批"。
     *
     * <p>不需要动作配置：批内水位早由 {@link #readBatch} 算好写进 {@code edge.readWatermark}（#79 曾带过
     * 一个 {@code ReadSqlConfig} 参数，但从加上那天起函数体就没引用过——清理时去掉）。</p>
     */
    void updateWatermark(Exchange exchange, Job job) {
        Long value = exchange.getProperty(RouteKeys.WATERMARK, Long.class);
        if (value != null) {
            watermarkStore.put("job:" + job.getId(), value);
        }
    }

    /** 循环条件：这一批之后还有下一批（keyset 分页没扫完）。 */
    boolean moreBatches(Exchange exchange) {
        return Boolean.TRUE.equals(exchange.getProperty(RouteKeys.HAS_MORE, Boolean.class));
    }

    // ---- WRITE_UPSERT：幂等写 ----

    /** 按动作选定客户库的连接池与方言写一行（幂等 upsert，写法归方言，ADR-0014）。 */
    void upsert(Exchange exchange, JdbcTemplate jdbc, String table, String idempotencyKey, SqlDialect dialect) {
        Map<String, Object> row = exchange.getMessage().getBody(Map.class);
        jdbc.update(buildUpsertSql(row, table, idempotencyKey, dialect), row.values().toArray());
    }

    /**
     * 生成幂等 upsert SQL（#67）：动态列 + 业务键，写法归方言（MySQL 的 {@code on duplicate key update}、
     * SQL Server 的 {@code MERGE}，ADR-0014）。包内可见以便单测校验幂等语义（同业务键重复执行不产生重复行）。
     */
    static String buildUpsertSql(Map<String, Object> row, String table, String idempotencyKey, SqlDialect dialect) {
        return dialect.upsert(table, new ArrayList<>(row.keySet()), idempotencyKey);
    }

    /** MySQL 方言的 upsert SQL：同包历史单测的入口；生产路径一律显式传方言（见上一个重载）。 */
    static String buildUpsertSql(Map<String, Object> row, String table, String idempotencyKey) {
        return buildUpsertSql(row, table, idempotencyKey, SqlDialects.of(DataSourceType.MYSQL));
    }

    // ---- HTTP 出站：响应捕获（#123 / #126）----

    /**
     * HTTP 出站后的收尾：把响应体解析进 {@code context[本动作序号]}，再把链上消息体还原成本次请求体。
     *
     * <p><b>恒捕获</b>——契约（{@code docs/action-chain-design.md} §2）本就要求每个动作完成后写
     * {@code context[actionIndex] = 输出}，HTTP 动作的输出就是响应，所以没有开关也不新增配置项。</p>
     *
     * <p><b>还原消息体</b>是 #126：Camel HTTP 组件默认把响应装进 body，于是响应顶替了请求数据——
     * 紧随其后的 {@code WRITE_UPSERT} 在空响应（204 / 空 200）上拿到 {@code null} 取 keySet 直接 NPE，
     * 一次**成功**的调用被判成整链失败。想继续往下传响应，就显式引用本动作序号
     * （下一步 FIELD_MAP 的 {@code sourceStep}，或后续动作的取源参数）。</p>
     *
     * <p><b>成功条件断言（#148）</b>插在「取到响应」之后、「写上下文与记账」之前：目标端
     * 200 但响应体写业务错时，这一步必须判失败。抛在 {@link StepLedger#recordSuccessStep} 之前，
     * 所以该步只有一行 FAILED（由 {@code ErrorHandling} 的 {@code onException} 写），不会先记一行 SUCCESS；
     * 断言没过也就走不到还原与写回，不会拿一份业务无效的响应去配对。</p>
     *
     * <p>出站地址与 HTTP 方法由建片段时算好的那份传进来（{@code ActionFragment} 建路由时已解析过一次，
     * 断言失败信息里要用的就是它），这里不重算。</p>
     *
     * <p>失败面不变：4xx/5xx 由 Camel HTTP 组件抛 {@code HttpOperationFailedException}，根本走不到这里
     * （不写 context、不执行后续写回），按 ADR-0003 失败即停 + 重试退避；断言失败按普通异常走同一套退避。</p>
     */
    void captureResponse(Exchange ex, Action action, int index, HttpCallConfig call, String method, String url) {
        Object request = ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class);
        String raw = ResponseTexts.text(ex);
        // 捕获内容不做截断：装不下就立即失败（可读错误），绝不写半截 JSON（#123 口径 4；列容量本身是 #141=MEDIUMTEXT）
        messageHistory.requireCaptureFitsSnapshot(index, raw);
        Object response = HttpCallResponse.parse(raw);
        // 在记账之前断言：不满足即抛，该步只有一行 FAILED
        HttpSuccessAssert.check(method, url, call.successPath(), call.successExpect(), response, raw);
        Map<Integer, Object> context = StepLedger.context(ex);
        context.put(index, response);
        ex.setProperty(RouteKeys.CONTEXT, context);
        ex.getMessage().setBody(request);
        // data_snapshot 语义不变（input = 请求、data = 响应）：记响应原文，不记解析后的对象
        ledger.recordSuccessStep(ex, action, request, raw);
    }

    // ---- 构建期解析：连接池 / 方言 / URL ----

    /** HTTP 出口的完整 URL：基准地址与路径拼接（路径空则原样返回基准）。 */
    String composeUrl(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        if (base.endsWith("/")) {
            return base + path.replaceFirst("^/", "");
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * 取动作选定的客户库连接池并确保其已登记为 {@code edge-ds-<id>} bean（懒建），
     * 供 READ_SQL 的 {@code #edge-ds-<id>} 与 WRITE_UPSERT 的 {@link JdbcTemplate} 使用。
     * 数据源缺失或不是关系库时注册期即失败，避免把读写错误落到错误库。
     */
    javax.sql.DataSource customerDataSource(Long dataSourceId) {
        javax.sql.DataSource pool = dataSourceManager.get(dataSourceId);
        if (pool == null) {
            throw new IllegalArgumentException("动作引用的数据源不可用（不是关系库或未配置）: " + dataSourceId);
        }
        return pool;
    }

    /** 取动作选定数据源的方言（与连接池同一份判断，池建好时记下）；拿不到时当场报错，不猜一个默认方言。 */
    SqlDialect requireDialect(Long dataSourceId) {
        SqlDialect dialect = dataSourceManager.dialect(dataSourceId);
        if (dialect == null) {
            throw new IllegalArgumentException("动作引用的数据源不可用（不是关系库或未配置）: " + dataSourceId);
        }
        return dialect;
    }
}
