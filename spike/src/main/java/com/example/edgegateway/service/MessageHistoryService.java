package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.BatchStamp;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.model.MessageStepIndex;
import com.example.edgegateway.model.RunStepSummary;
import com.example.edgegateway.model.RunStepTotals;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.StreamCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 消息历史服务：把死信升维为"运行 + 消息步"（ADR-0009）。
 * 负责在任务执行链路上记录每次运行与每条消息穿过每个动作的结果（成功/失败全程），
 * 供历史页面按运行/消息/业务键检索，并支持从失败消息步精确断点续跑（人工重投）。
 */
@Service
public class MessageHistoryService {

    private static final Logger LOG = LoggerFactory.getLogger(MessageHistoryService.class);

    public static final String RUN_ID = "edge.runId";
    public static final String MESSAGE_ID = "edge.messageId";
    public static final String BUSINESS_KEY = "edge.businessKey";
    public static final String ACTION_INDEX = "edge.actionIndex";
    public static final String ACTION_NAME = "edge.actionName";
    public static final String STEP_INPUT = "edge.stepInput";
    public static final String RESUME_FROM = "edge.resumeFromStep";
    /** 断点续跑的上游上下文：{@code Map<Integer step, Object data>}，值取上游 SUCCESS 消息步的 data_snapshot（#90）。 */
    public static final String RESUME_CONTEXT = "edge.resumeContext";
    /**
     * 当前消息的**批号**（{@code edge.batchMessageId}）：这一步所属的那一批的编号（#139 / ADR-0017）。
     *
     * <p>读库那条路**每读一批现生成一个**（`RouteGenerator.readBatch`），这一批的每条源行共用它，
     * 并随消息在链上**继承到下一个基数变化动作为止**。它还有第二个用途：读库动作在**读到第一批之前**
     * 就失败时，一条源行都没产生，失败步拿它当**占位消息标识**（那一步没有消息可挂）。
     * MQ 消费 / 任务输入那些链没有它（`null`）——那些链永远不出现批次。</p>
     */
    public static final String BATCH_MSG_ID = "edge.batchMessageId";
    /**
     * 当前消息的**批内序号**（#139，{@code edge.batchSeq}）：从 {@code 1} 起（「这批里第几条」）。
     *
     * <p>它不是「这一步的批号」（那是 {@link #BATCH_MSG_ID}），而是这条消息在那一批里的位置。
     * 读库那条路**不设它**——读取批里「第几条」没有额外语义，那些行留空（空不是 0）；拆分与合并设。</p>
     */
    public static final String BATCH_SEQ = "edge.batchSeq";
    /** 当前消息的**父消息标识**（#139，{@code edge.parentMessageId}）：从哪条消息拆出来的，拆分动作设。 */
    public static final String PARENT_MESSAGE_ID = "edge.parentMessageId";
    /**
     * 合并那一批的**成员清单**（#142）挂在 exchange 上的属性名：还原与出站失败时的补记
     * 都用它把「这一批哪几条消息、各自那一片是什么」传下去。
     * 名字的唯一定义在这儿（路由生成器只是引它）。
     */
    public static final String BATCH_MANIFEST = "edge.batchManifest";

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String REDELIVERED = "REDELIVERED";

    /**
     * 消息步快照列（{@code data_snapshot} / {@code input_snapshot}）的字节上限：**{@code MEDIUMTEXT} 的
     * 16777215 字节（16 MB）**。
     *
     * <p>与列宽**同一个数**是有意的（#141）：它跟着
     * {@code db/migration/V6__snapshot_mediumtext.sql} 走——迁移把承载报文的三列提到 {@code MEDIUMTEXT}，
     * 这里就写 {@code MEDIUMTEXT} 的上限。只有列型再变时才动这个数，**别单独调它**（调了就是三处各说各话：
     * 代码、产品 schema、迁移）。</p>
     *
     * <p>超过它的快照按失败处理而不是截断（#123 口径 4）。放宽这道界线本身是 #141（原 #127）。</p>
     */
    static final int SNAPSHOT_LIMIT_BYTES = 16_777_215;

    private final JobRunMapper jobRunMapper;
    private final MessageStepMapper messageStepMapper;
    private final JobMapper jobMapper;
    private final ActionMapper actionMapper;
    private final ObjectMapper objectMapper;
    private final CamelContext camelContext;
    private final JobLastRunWriter jobLastRunWriter;

    public MessageHistoryService(
            JobRunMapper jobRunMapper,
            MessageStepMapper messageStepMapper,
            JobMapper jobMapper,
            ActionMapper actionMapper,
            ObjectMapper objectMapper,
            CamelContext camelContext,
            JobLastRunWriter jobLastRunWriter) {
        this.jobRunMapper = jobRunMapper;
        this.messageStepMapper = messageStepMapper;
        this.jobMapper = jobMapper;
        this.actionMapper = actionMapper;
        this.objectMapper = objectMapper;
        this.camelContext = camelContext;
        this.jobLastRunWriter = jobLastRunWriter;
    }

    // ---------- 运行（job_run） ----------

    public JobRun startRun(Long jobId, Object input) {
        JobRun run = new JobRun();
        run.setJobId(jobId);
        run.setStatus("RUNNING");
        run.setInputSnapshot(toJson(input));
        run.setStartedAt(LocalDateTime.now());
        jobRunMapper.insert(run);
        return run;
    }

    /**
     * 结束一次运行（各链路尾部统一调用）：写 job_run，并把本次结果回写到任务
     * {@code last_run_status/last_run_at/last_error}（#88，覆盖消费/调度/手动/重投）。
     * 已完成（SUCCESS/FAILED）的运行不受重复 finish 影响，也不再改写任务上的最近运行结果。
     */
    public void finishRun(Long runId, String status, String error) {
        if (runId == null) {
            return;
        }
        JobRun run = jobRunMapper.selectById(runId);
        if (run == null || "SUCCESS".equals(run.getStatus()) || "FAILED".equals(run.getStatus())) {
            return; // 已完成不受重复 finish 影响
        }
        run.setStatus(status);
        run.setError(error);
        run.setFinishedAt(LocalDateTime.now());
        jobRunMapper.updateById(run);
        jobLastRunWriter.write(run.getJobId(), run.getStatus(), run.getError(), run.getFinishedAt());
    }

    // ---------- 消息步（message_step） ----------

    /**
     * 记一条成功消息步。{@code durationMs} 是这一步的**动作耗时**（#134，毫秒）；
     * 量不出来时传 {@code null}——不编 0（「没测」与「测得就是 0 ms」在页面上是两件事）。
     *
     * <p>{@code batch} 是这一行的**批次戳**（#139：批号 / 批内序号 / 父消息标识）。两条写库的路
     * （成功、失败）都带它——只记一条路的话，「哪几条是同一批」在失败那一格上就断了。</p>
     */
    public void recordSuccess(Long runId, String messageId, Integer step, String actionName,
                              Object input, Object data, String businessKey, int attempt, Long durationMs,
                              BatchStamp batch) {
        record(runId, messageId, step, actionName, SUCCESS, data, input, null, businessKey, attempt, durationMs, batch);
    }

    /**
     * 记一条失败消息步（失败步也记耗时：#134 里最该看耗时的往往就是失败的那一步；批次戳同上）。
     */
    public void recordFailure(Long runId, String messageId, Integer step, String actionName,
                              String error, Object input, String businessKey, int attempt, Long durationMs,
                              BatchStamp batch) {
        record(runId, messageId, step, actionName, FAILED, null, input, error, businessKey, attempt, durationMs, batch);
    }

    private void record(Long runId, String messageId, Integer step, String actionName, String status,
                        Object data, Object input, String error, String businessKey, int attempt,
                        Long durationMs, BatchStamp batch) {
        if (runId == null || messageId == null || step == null) {
            return;
        }
        MessageStep existing = messageStepMapper.selectOne(new LambdaQueryWrapper<MessageStep>()
                .eq(MessageStep::getRunId, runId)
                .eq(MessageStep::getMessageId, messageId)
                .eq(MessageStep::getStep, step));
        MessageStep entity = existing == null ? new MessageStep() : existing;
        entity.setRunId(runId);
        entity.setMessageId(messageId);
        entity.setStep(step);
        entity.setActionName(actionName);
        entity.setStatus(status);
        entity.setDataSnapshot(toJson(data));
        entity.setInputSnapshot(toJson(input));
        entity.setError(error);
        entity.setAttempt(attempt);
        entity.setDurationMs(durationMs);
        entity.setBusinessKey(businessKey);
        entity.applyBatch(batch);
        if (existing == null) {
            entity.setCreatedAt(LocalDateTime.now());
            messageStepMapper.insert(entity);
        } else {
            messageStepMapper.updateById(entity);
        }
    }

    /**
     * 捕获内容（HTTP 响应，**大小由目标端决定、本地拦不住**）装不装得进 {@code data_snapshot}（#123 口径 4）：
     * 装不下就当场**显式失败**，绝不写半截 JSON——写半截的话，断点续投会拿着一个语法都不完整的上游快照去
     * 重建 context，报出来的错离现场十万八千里。
     *
     * <p>只给捕获路径调用。其余动作的快照行为一概不变（本地自己的数据，超容量时照旧由写库报
     * {@code Data too long}、动作失败）——这道闸的**行为**没变，变的只是界线：列提到 {@code MEDIUMTEXT}
     * 之后 {@link #SNAPSHOT_LIMIT_BYTES} 跟着抬到 16 MB（#141）。</p>
     */
    void requireCaptureFitsSnapshot(Integer step, Object captured) {
        String json = toJson(captured);
        if (json == null) {
            return;
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > SNAPSHOT_LIMIT_BYTES) {
            throw new SnapshotTooLargeException("消息步 " + step + " 的 data_snapshot 有 " + bytes
                    + " 字节，超过快照列容量 " + SNAPSHOT_LIMIT_BYTES + " 字节：按失败处理，不写半截（#123 / #141）");
        }
    }

    /** 快照超容量（#123 口径 4）：显式失败，绝不写半截。 */
    static final class SnapshotTooLargeException extends IllegalStateException {
        SnapshotTooLargeException(String message) {
            super(message);
        }
    }

    // ---------- 检索 ----------

    /**
     * 运行列表。
     *
     * <p>{@code businessKey} 的口径（#129）：**该运行下任一条消息的业务键命中就保留这条运行**。
     * 实现（#130 收口，ADR-0016 结果 ⑥）：**一次查询**，命中判据下推成 {@code EXISTS} 子查询——
     * 旧实现是「先 `LIKE` 扫 {@code message_step} 拿全部候选 runId，再 `IN (...)` 筛运行」，
     * 一条业务键命中很多运行时就是一个无界 `IN`。</p>
     */
    public Map<String, Object> listRuns(Long jobId, String status, String businessKey, int page, int size) {
        Page<JobRun> result = jobRunMapper.selectPage(new Page<>(page, size),
                matchingRuns(jobId, status, businessKey).orderByDesc(JobRun::getId));
        return pageOf(result.getTotal(), result.getRecords());
    }

    /**
     * 计数条与运行摘要那条只读聚合接口（#130）。
     *
     * <p>回答两个问题：**这次筛选命中了多大规模**（四个计数，口径是当前筛选集、**不含分页**）
     * 与**这条运行值不值得点开**（每运行摘要）。两组数字与运行列表同一组筛选算出来，
     * 所以界面上的行摘要与展开后按 runId 取到的消息步数对得上。</p>
     *
     * <p>三个数与运行列表同一口径：`status` 命中 {@code job_run.status}（不是某个消息步的状态，
     * 与 `/history/runs` 完全同一个参数语义）；命中的业务键也按同一口径（该运行下任一消息命中）筛。</p>
     *
     * <p><b>摘要有页、计数没有页</b>：四个计数是**整个筛选集**的（票面：「翻页不影响计数」），
     * 而每运行摘要只给这一页的行——界面上用得到的就这一页。全量回一次实测两三千条时要 1 MB
     * （业务键那一格最占地方），换来的是没人看的数字。调用方按运行列表的 page/size 同步传，
     * 摘要便一行不差地对上。</p>
     */
    public Map<String, Object> runsSummary(Long jobId, String status, String businessKey, int page, int size) {
        Long runs = jobRunMapper.selectCount(matchingRuns(jobId, status, businessKey));
        Page<JobRun> pageOfRuns = jobRunMapper.selectPage(new Page<>(page, size),
                matchingRuns(jobId, status, businessKey).orderByDesc(JobRun::getId));

        // 摘要是**这一页**的行：与运行列表同一顺序，按 runId 给出去，界面自己按行对
        List<Long> runIds = pageOfRuns.getRecords().stream().map(JobRun::getId).toList();
        Map<Long, RunStepSummary> byRunId = summarizeSteps(runIds);
        // 命中的业务键**只在筛了业务键时才有内容**：没筛的时候把每条运行的全部业务键都回来，
        // 一页二十行就是几十个没人看的标签（实测 runs=2682 时那一格占了大半 payload），
        // 而界面上那一块本来也只在「业务键筛出结果」时才有话要说
        boolean keyFiltered = businessKey != null && !businessKey.isBlank();
        Map<Long, List<String>> keysByRunId = keyFiltered ? businessKeysOf(runIds) : Map.of();
        List<Map<String, Object>> summaries = new ArrayList<>(runIds.size());
        for (Long runId : runIds) {
            RunStepSummary summary = byRunId.get(runId);
            summaries.add(Map.of(
                    "runId", runId,
                    "messageCount", summary == null ? 0L : summary.getMessageCount(),
                    // 一格叫 stepCount：`steps` 这个名字留给**整条筛选集**的那个数（计数条），
                    // 同一个词在两条 scope 上各指一个范围是这类接口最容易读错的地方
                    "stepCount", summary == null ? 0L : summary.getStepCount(),
                    "failedSteps", summary == null ? 0L : summary.getFailedSteps(),
                    // Map.of 不收 null 值：业务键可为空，这里用 List.of() 表示「没有可显示的业务键」
                    "matchedBusinessKeys", keyFiltered
                            ? matchedKeys(keysByRunId.get(runId), businessKey) : List.of()));
        }

        Map<String, Object> totals = stepTotals(jobId, status, businessKey);
        return Map.of(
                "runs", runs,
                "messages", totals.get("messages"),
                "steps", totals.get("steps"),
                "failedSteps", totals.get("failedSteps"),
                "summaries", summaries);
    }

    /**
     * 整个筛选集的消息 / 消息步 / 失败步三个数（#130 的计数条，**不含分页**）。
     *
     * <p>一条 SQL 出结果：命中运行的判据（任务 / 状态 / **业务键**）下推给
     * {@link MessageStepMapper#summarizeMatchingRuns}，**不把命中运行的 id 拉回内存再拼 `IN`**
     * ——整库运行时那个 `IN` 是无界的，而这条路上的「命中运行」就是整库。</p>
     *
     * <p>业务键**必须**在这一组条件里：曾经只用任务 / 状态算这三个数，于是「业务键筛出一条也没命中」
     * 时计数条写着「运行 0 / 消息 5478」——一个筛选集里运行是 0 而消息不是 0，自己打自己的脸。</p>
     */
    private Map<String, Object> stepTotals(Long jobId, String status, String businessKey) {
        RunStepTotals totals = messageStepMapper.summarizeMatchingRuns(jobId,
                status == null ? null : status.trim(), likePattern(businessKey));
        // 没有任何消息步时 SQL 仍回一行（0 / 0 / 0）；这一层只兜「一条 SQL 也没回」这种不可能的情况
        return Map.of(
                "messages", totals == null ? 0L : totals.getMessageCount(),
                "steps", totals == null ? 0L : totals.getStepCount(),
                "failedSteps", totals == null ? 0L : totals.getFailedSteps());
    }

    /** 「哪些运行属于当前筛选集」的**唯一定义**（#130）：运行列表分页、计数条的计数、每运行摘要
     * 都从这一处来。同一个条件写两遍，就是「列表说有 3 条、计数说 5 条」那类对不上的来源。
     */
    private LambdaQueryWrapper<JobRun> matchingRuns(Long jobId, String status, String businessKey) {
        LambdaQueryWrapper<JobRun> wrapper = new LambdaQueryWrapper<>();
        if (jobId != null) {
            wrapper.eq(JobRun::getJobId, jobId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(JobRun::getStatus, status.trim());
        }
        String pattern = likePattern(businessKey);
        if (pattern != null) {
            // 一次查询：命中判据下推成 EXISTS 子查询（旧实现是「LIKE 扫 message_step 拿 runId，
            // 再 IN 筛运行」——命中很多运行时那个 IN 是无界的）。
            // 为什么不走 apply(sql, 参数)：MyBatis-Plus 3.5.17 这条重载在这里**没绑上参数**
            // （`apply("status = {0}", "SUCCESS")` 连 FAILED 的行一起返回，H2 真 SQL 测试抓到的），
            // 所以按老办法把模式串内联进子查询，值先过 {@link #likeLiteral}。
            wrapper.inSql(JobRun::getId, "SELECT s.run_id FROM message_step s"
                    + " WHERE s.business_key LIKE " + likeLiteral(pattern));
        }
        return wrapper;
    }

    /**
     * 业务键的 `LIKE` 模式串（`%关键词%`）；没筛业务键时给 {@code null}（SQL 里就不带那个 EXISTS）。
     *
     * <p>**这里刻意不转义 `%` / `_`**：整条链路上业务键的子串匹配都走同一档语义——消息步检索那一侧
     * （{@link #listSteps}）用的是 MyBatis-Plus 的 `like()`，它也不转义。只在这一侧加转义，同一个业务键
     * 会在两个模式里筛出不同的集合（#130 审查抓到的正是这个「两边口径不一致」）。</p>
     *
     * <p>要改成「字面量匹配」得三条路一起改（两处 SQL + 界面说明），那是另一张票；MySQL 的 `\` 默认
     * 转义在 H2 上也不成立（真 SQL 测试跑 H2），单边转义连测试都过不了。</p>
     */
    private String likePattern(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        return "%" + businessKey.trim() + "%";
    }

    /**
     * 把模式串变成可以内联进 SQL 的字符串字面量（模式串本身是拼出来的，只能内联，见 {@link #matchingRuns}）。
     *
     * <p>两步：`'` 翻倍（别把用户的引号当成字符串结束）；`\` 翻倍（MySQL 的 `LIKE` 默认拿 `\` 当转义符，
     * 不翻倍的话「业务键里带反斜杠」会让后面的字符被静默丢弃——找一个包含 `\` 的业务键正是会触发的那一步）。
     * `%` / `_` 不动：它们是通配符，与消息步检索那一侧同一档语义。</p>
     */
    private static String likeLiteral(String pattern) {
        return "'" + pattern.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * 给这一页的运行按 run_id 聚合消息步（一次查询）；没有消息步的运行不在结果里，由调用方补 0。
     *
     * <p>**输入只该是当前这一页的运行**：`summarizeByRunIds` 的 `WHERE run_id IN (...)` 是无界的，
     * 整条筛选集的合计走 {@link #stepTotals}（判据下推，一条 SQL）。</p>
     */
    private Map<Long, RunStepSummary> summarizeSteps(List<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RunStepSummary> byRunId = new HashMap<>();
        messageStepMapper.summarizeByRunIds(runIds)
                .forEach(summary -> byRunId.put(summary.getRunId(), summary));
        return byRunId;
    }

    /**
     * 命中运行下出现过的业务键（去重）：运行侧「命中的业务键」小标签（#130）与
     * ADR-0016 结果 ⑤ 里 #129 留下的那处缺口（「只筛不显示」）都靠它。
     *
     * <p>只要 run_id 与 business_key 两列——整行读回来会白读 `message_step` 上那两个 TEXT 快照列。</p>
     */
    private Map<Long, List<String>> businessKeysOf(List<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> keys = new HashMap<>();
        messageStepMapper.selectList(new LambdaQueryWrapper<MessageStep>()
                        .select(MessageStep::getRunId, MessageStep::getBusinessKey)
                        .in(MessageStep::getRunId, runIds)
                        .isNotNull(MessageStep::getBusinessKey))
                .forEach(step -> {
                    List<String> perRun = keys.computeIfAbsent(step.getRunId(), id -> new ArrayList<>());
                    if (!perRun.contains(step.getBusinessKey())) {
                        perRun.add(step.getBusinessKey());
                    }
                });
        return keys;
    }

    /**
     * 命中的业务键：只留**子串命中**的那几个（大小写敏感——与 SQL 那边一样，同一份业务键
     * 不该在两处被判成不同结果）。调用方只在筛了业务键时才走这里。
     */
    private List<String> matchedKeys(List<String> keys, String businessKey) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        String needle = businessKey.trim();
        return keys.stream().filter(key -> key.contains(needle)).toList();
    }

    /**
     * 消息步列表（#74 契约的检索面，#136 起每行还带上所属运行的 {@code jobId}）。
     *
     * <p>{@code businessKey} 的口径：**按该步所属消息的业务键**匹配（{@code LIKE} 子串，
     * 与运行那一侧同一套）；{@code status} 为空时不带状态条件——「默认只看 FAILED（#74）」
     * 是**界面（调用方）的口径**，不在这里兜底，否则这一层的调用者再也没法说「全部状态」。</p>
     */
    public Map<String, Object> listSteps(Long jobId, Long runId, String messageId, String businessKey,
                                         String status, int page, int size) {
        LambdaQueryWrapper<MessageStep> wrapper = new LambdaQueryWrapper<MessageStep>()
                .orderByDesc(MessageStep::getId);
        if (jobId != null) {
            List<Long> runIds = jobRunMapper.selectList(new LambdaQueryWrapper<JobRun>()
                            .eq(JobRun::getJobId, jobId))
                    .stream()
                    .map(JobRun::getId)
                    .toList();
            if (runIds.isEmpty()) {
                return pageOf(0L, List.of());
            }
            wrapper.in(MessageStep::getRunId, runIds);
        }
        if (runId != null) {
            wrapper.eq(MessageStep::getRunId, runId);
        }
        if (messageId != null && !messageId.isBlank()) {
            wrapper.eq(MessageStep::getMessageId, messageId.trim());
        }
        if (businessKey != null && !businessKey.isBlank()) {
            wrapper.like(MessageStep::getBusinessKey, businessKey.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(MessageStep::getStatus, status.trim());
        }
        Page<MessageStep> result = messageStepMapper.selectPage(new Page<>(page, size), wrapper);
        attachRunJobIds(result.getRecords());
        return pageOf(result.getTotal(), result.getRecords());
    }

    /**
     * 给这一页消息步补上所属运行的 {@code jobId}（#136）：左列表的消息步行要写「任务 #id」，
     * 而消息步自己只带 {@code runId}。**按页一次 {@code in} 查询**补齐——不在前端按行补拉运行
     * （ADR-0016 决策 6 拒绝 N+1 同一个理由）。运行查不到（脏数据）时该步留空，界面写「任务 —」。
     */
    private void attachRunJobIds(List<MessageStep> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> runIds = records.stream()
                .map(MessageStep::getRunId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (runIds.isEmpty()) {
            return;
        }
        Map<Long, Long> jobIds = new HashMap<>();
        // 只要 id 与 jobId 两列：job_run 上还挂着快照列（`input_snapshot`，MEDIUMTEXT，最大 16 MB）与 error，
        // 整行读回来纯属白读（#141）
        jobRunMapper.selectList(new LambdaQueryWrapper<JobRun>()
                        .select(JobRun::getId, JobRun::getJobId)
                        .in(JobRun::getId, runIds))
                .forEach(run -> jobIds.put(run.getId(), run.getJobId()));
        records.forEach(step -> step.setJobId(step.getRunId() == null ? null : jobIds.get(step.getRunId())));
    }

    /**
     * 一条运行的**消息步索引**（#131）：右详情（消息 chips + 步骤链 + 单步卡）要的那几列，
     * **不含两个 TEXT 快照列**——合上票面那句「展开一条运行只取前 100 个消息步」的隐藏上限，
     * 靠的不是把上限调大，而是把一行从「几十 KB」压到「两百字节」：一条 720 步的运行
     * （实测库里最大的一条）整条索引 100 KB 上下，一次全拿得动，不必再截断。
     *
     * <p>要看快照本身，点开那一步再按 id 取整行（{@link #getStep}）——#132 的快照面板走这条路。</p>
     *
     * <p>运行不存在时与 {@link #getRun} 同一个口径（404）：界面上只会拿运行列表里的 id 来问，
     * 真问到不存在的 id 是调用方错了，不该安静地回一个空列表（那会把「运行没了」装成
     * 「这条运行没有步」）。存在性只查 id 一列——{@code job_run} 上也挂着 TEXT 快照列，
     * 整行读回来是白读（与 {@link #attachRunJobIds} 同一个理由）。</p>
     */
    public Map<String, Object> runStepIndex(Long runId, int page, int size) {
        List<JobRun> found = jobRunMapper.selectList(new LambdaQueryWrapper<JobRun>()
                .select(JobRun::getId)
                .eq(JobRun::getId, runId));
        if (found.isEmpty()) {
            throw new NoSuchElementException("运行不存在: " + runId);
        }
        Page<MessageStepIndex> result = messageStepMapper.pageRunStepIndex(new Page<>(page, size), runId);
        return pageOf(result.getTotal(), result.getRecords());
    }

    public JobRun getRun(Long id) {
        JobRun run = jobRunMapper.selectById(id);
        if (run == null) {
            throw new NoSuchElementException("运行不存在: " + id);
        }
        return run;
    }

    public MessageStep getStep(Long id) {
        MessageStep step = messageStepMapper.selectById(id);
        if (step == null) {
            throw new NoSuchElementException("消息步不存在: " + id);
        }
        return step;
    }

    /**
     * 这条消息步属于哪个任务（经它的运行行找）。重投的两个入口（{@code HistoryController}）用它取
     * 任务标识去做任务串行判断（#146）；任务标识取不到时返回 {@code null}，由调用方按原有口径报错
     * （不存在 / 未启用 / 仅失败步可重投），此处不改判。
     */
    public Long jobIdOfStep(Long stepId) {
        MessageStep step = messageStepMapper.selectById(stepId);
        if (step == null || step.getRunId() == null) {
            return null;
        }
        JobRun run = jobRunMapper.selectById(step.getRunId());
        return run == null ? null : run.getJobId();
    }

    /**
     * 从一条失败消息步精确断点续跑：用该步数据从该动作重放，成功后原步标记 REDELIVERED。
     */
    public MessageStep redeliver(Long stepId) {
        MessageStep step = getStep(stepId);
        if (!FAILED.equals(step.getStatus())) {
            throw new IllegalStateException("仅失败消息步可重投: " + stepId);
        }
        JobRun run = jobRunMapper.selectById(step.getRunId());
        if (run == null) {
            throw new IllegalStateException("消息步所属运行不存在: " + step.getRunId());
        }
        Job job = jobMapper.selectById(run.getJobId());
        if (job == null || job.getStatus() == null || job.getStatus() != 1) {
            throw BusinessException.of(BusinessCode.JOB_NOT_ENABLED, "任务未启用，无法重投");
        }
        // 源动作（READ_SQL/MQ_CONSUME）失败时没有断点数据可续跑：再次重投会用空数据执行下游，导致下游一并失败
        if (isSourceAction(run.getJobId(), step.getStep())) {
            throw new IllegalStateException("源动作（READ_SQL/MQ_CONSUME）失败无断点数据，无法断点重投；请重新执行任务");
        }
        Object input = parseJson(step.getInputSnapshot());
        Map<Integer, Object> resumeContext = upstreamContext(step);
        try (ProducerTemplate producer = camelContext.createProducerTemplate()) {
            Exchange result = producer.request("direct:job-" + job.getId() + "-resume", ex -> {
                ex.getMessage().setBody(input);
                ex.setProperty(RESUME_FROM, step.getStep());
                // #90：被跳过的上游步骤输出随重投回填 context，引用上游输出的 FIELD_MAP 也能续跑（缺快照则不注入）
                ex.setProperty(RESUME_CONTEXT, resumeContext);
                // 断点续跑沿用原业务键，重投后的消息步仍按同一业务实体分组/检索
                ex.setProperty(BUSINESS_KEY, step.getBusinessKey());
                // #139：批次戳也沿用——重投是**同一个消息步的重跑**，它属于哪一批、从哪条消息来都不变。
                // 断点续跑路由跳过了读库那一步（`step < resumeFrom`），批号因此不是现生成的：不沿下来
                // 的话这几行的 batch_id 是空的，右详情会把一条本来在某批里的链画成「单条 · 不是一批」。
                ex.setProperty(BATCH_MSG_ID, step.getBatchId());
                ex.setProperty(BATCH_SEQ, step.getBatchSeq());
                ex.setProperty(PARENT_MESSAGE_ID, step.getParentMessageId());
            });
            failIfExchangeFailed(result);
        } catch (Exception e) {
            throw new IllegalStateException("重投失败: " + rootMessage(e), e);
        }
        step.setStatus(REDELIVERED);
        messageStepMapper.updateById(step);
        return step;
    }

    /**
     * 重投"真的成功了"才算成功（#108）：{@code ProducerTemplate.request(uri, processor)} 把异常留在
     * exchange 上、**不重抛**，于是失败的重投会安静地返回；这里补上那道检查，把失败交给调用方
     * ——否则接口返回 OK、原步被标 {@code REDELIVERED}，而目标端根本没拿到数据（运维据此收工）。
     * 取不到 exchange（mock/桩）时按成功处理，与旧行为一致。
     *
     * <p>只抛根因，不加"重投失败:"前缀——前缀由 {@link #redeliver} 的 catch 统一加**一次**，
     * 否则调用方看到的是"重投失败: 重投失败: &lt;根因&gt;"。</p>
     */
    private void failIfExchangeFailed(Exchange exchange) {
        if (exchange == null) {
            return;
        }
        Exception failure = exchange.getException();
        if (failure == null) {
            failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        }
        if (failure != null) {
            throw new IllegalStateException(rootMessage(failure), failure);
        }
        if (exchange.isFailed()) {
            // 失败但没有异常对象：只说得出"失败了"，别编原因
            throw new IllegalStateException("续跑路由未成功结束");
        }
    }

    /**
     * 取该步之前（同一运行、同一消息）已 SUCCESS 的上游步骤输出，供断点续跑回填 context（#90）：
     * 被跳过的步骤不再让下游规则静默映射出空。无快照、或快照不是 JSON 对象的步骤不注入该键，行为与现状一致。
     */
    private Map<Integer, Object> upstreamContext(MessageStep step) {
        Map<Integer, Object> context = new LinkedHashMap<>();
        if (step.getStep() == null) {
            return context; // 无步骤序号就无从判断"更早"，不注入（行为与现状一致）
        }
        List<MessageStep> upstream = messageStepMapper.selectList(new LambdaQueryWrapper<MessageStep>()
                .eq(MessageStep::getRunId, step.getRunId())
                .eq(MessageStep::getMessageId, step.getMessageId())
                .lt(MessageStep::getStep, step.getStep())
                .eq(MessageStep::getStatus, SUCCESS));
        upstream.stream()
                .filter(s -> s.getStep() != null)
                .sorted(Comparator.comparing(MessageStep::getStep))
                .forEach(s -> {
                    Object data = parseSnapshot(s.getDataSnapshot());
                    if (data != null) {
                        context.put(s.getStep(), data);
                    }
                });
        return context;
    }

    /**
     * 解析上游快照并回填 context：**任意 JSON 值**都算数——对象 / 数组 / 标量各自还原成 Map / List / 原值。
     * HTTP 响应做成数组或标量的情形（#123）因此也能续投，而不是静默丢掉该键、让下游映射出空。
     *
     * <p>只挡一种：解析不出来的**非 JSON 文本**（如目标端回一句 {@code ok}）——那份快照与一段乱码无从区分，
     * 宁可当作"没有快照"（该步骤不注入），也不把来路不明的文本当成上游输出。任何情况下都不因此阻断整次重投
     * （与"无快照则不注入"同一条边界）。</p>
     */
    private Object parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot, Object.class);
        } catch (Exception notJson) {
            return null;
        }
    }

    /** 该消息步是否为链首源动作（READ_SQL/MQ_CONSUME）：此类失败没有可续跑的数据。 */
    private boolean isSourceAction(Long jobId, Integer step) {
        if (step == null) {
            return false;
        }
        return actionMapper.selectList(new LambdaQueryWrapper<Action>()
                        .eq(Action::getJobId, jobId)
                        .eq(Action::getActionIndex, step))
                .stream()
                .anyMatch(a -> a.getType() == ActionKind.READ_SQL || a.getType() == ActionKind.MQ_CONSUME);
    }

    // ---------- 工具 ----------

    private Map<String, Object> pageOf(long total, List<?> records) {
        return Map.of("total", total, "records", records);
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("消息步输入不是 JSON 对象，无法重投", e);
        }
    }

    private String toJson(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof String s) {
            return s;
        }
        if (input instanceof StreamCache cache) {
            try {
                cache.reset();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                cache.writeTo(out);
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return String.valueOf(input);
            }
        }
        if (input instanceof InputStream in) {
            try (InputStream stream = in) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return String.valueOf(input);
            }
        }
        if (input instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? throwable.getMessage() : message;
    }
}
