package com.example.edgegateway.web;

import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.service.BusinessCode;
import com.example.edgegateway.service.BusinessException;
import com.example.edgegateway.service.JobRunGuard;
import com.example.edgegateway.service.MessageHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 消息历史 REST：运行列表/详情、消息步列表/详情（默认 FAILED），并从失败消息步断点续跑。 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryController.class);

    /** 列表接口一页最多多少条（整行带两个 TEXT 快照，见 {@link #safeSize(int)}）。 */
    private static final int LIST_MAX_SIZE = 100;

    /** 消息步索引一页最多多少条（#131）：索引行不带快照，能宽十倍。 */
    private static final int INDEX_MAX_SIZE = 1000;

    private final MessageHistoryService messageHistoryService;
    private final JobRunGuard runGuard;

    public HistoryController(MessageHistoryService messageHistoryService, JobRunGuard runGuard) {
        this.messageHistoryService = messageHistoryService;
        this.runGuard = runGuard;
    }

    /** 历史列表（#74 契约）：按 jobId/runId/messageId/businessKey/status 过滤 + 分页，等价于 /steps。 */
    @GetMapping
    public Map<String, Object> listHistory(@RequestParam(required = false) Long jobId,
                                           @RequestParam(required = false) Long runId,
                                           @RequestParam(required = false) String messageId,
                                           @RequestParam(required = false) String businessKey,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return messageHistoryService.listSteps(jobId, runId, messageId, businessKey, status,
                safePage(page), safeSize(size));
    }

    /**
     * 运行列表。
     *
     * <p>{@code businessKey} 的口径（#129）：该运行下**任一条消息**的业务键命中，就保留这条运行。</p>
     */
    @GetMapping("/runs")
    public Map<String, Object> listRuns(@RequestParam(required = false) Long jobId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String businessKey,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return messageHistoryService.listRuns(jobId, status, businessKey, safePage(page), safeSize(size));
    }

    @GetMapping("/runs/{id}")
    public JobRun getRun(@PathVariable Long id) {
        return messageHistoryService.getRun(id);
    }

    /**
     * 一条运行的**消息步索引**（#131）：右详情一次把这条运行的全部消息步拿回去，画消息 chips、
     * 步骤链与单步卡。
     *
     * <p>与 {@link #listSteps} 的分工：那条是**检索面**（跨运行按任务 / 状态 / 业务键找步，
     * 最新在上，整行带快照）；这条是**一条运行的步骤顺序**（id 升序，只有画界面要的那几列，
     * 两个 TEXT 快照列不取——理由见 {@code MessageStepIndex}）。上一票那个「展开一条运行只取
     * 前 100 个消息步」的隐藏上限就死在这里：索引行小到可以一次拿全。</p>
     *
     * <p>{@code size} 的上限比列表那一侧宽十倍（1000）：列表一行要带快照，这里不带。</p>
     */
    @GetMapping("/runs/{id}/steps")
    public Map<String, Object> listRunStepIndex(@PathVariable Long id,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "500") int size) {
        return messageHistoryService.runStepIndex(id, safePage(page), safeSize(size, INDEX_MAX_SIZE));
    }

    /**
     * 计数条与运行摘要（#130）：与 {@link #listRuns} **同一组筛选参数**，回四个计数
     * （运行 / 消息 / 消息步 / 失败步，口径是当前筛选集、**不含分页**）与每运行摘要。
     *
     * <p>{@code page} / {@code size} 只决定**摘要给哪一页的运行**（调用方按运行列表同步传，
     * 行摘要才一行不差地对上）；四个计数与它们无关——票面：「翻页不影响计数」。</p>
     *
     * <p>路径不必排在 {@code /runs/{id}} 前面：按段精确匹配优先于模板变量。</p>
     */
    @GetMapping("/runs/summary")
    public Map<String, Object> runsSummary(@RequestParam(required = false) Long jobId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String businessKey,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return messageHistoryService.runsSummary(jobId, status, businessKey, safePage(page), safeSize(size));
    }

    @GetMapping("/steps")
    public Map<String, Object> listSteps(@RequestParam(required = false) Long jobId,
                                         @RequestParam(required = false) Long runId,
                                         @RequestParam(required = false) String messageId,
                                         @RequestParam(required = false) String businessKey,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return messageHistoryService.listSteps(jobId, runId, messageId, businessKey, status,
                safePage(page), safeSize(size));
    }

    @GetMapping("/steps/{id}")
    public MessageStep getStep(@PathVariable Long id) {
        return messageHistoryService.getStep(id);
    }

    /** 消息步详情（#74 契约）：等价于 /steps/{id}。 */
    @GetMapping("/{id}")
    public MessageStep getHistory(@PathVariable Long id) {
        return messageHistoryService.getStep(id);
    }

    /**
     * 从失败消息步断点续跑；成功后原消息步状态改为 REDELIVERED。
     *
     * <p>#146：重投会为这个任务**再开一次运行**，所以它与手动执行、外部触发、定时调度共用同一张占用表
     * ——已有运行在跑时直接拒（409 + {@code JOB_ALREADY_RUNNING}），不排队。</p>
     */
    @PostMapping("/steps/{id}/redeliver")
    public Map<String, Object> redeliver(@PathVariable Long id) {
        JobRunGuard.Handle handle = acquireForRedeliver(id);
        try {
            MessageStep step = messageHistoryService.redeliver(id);
            return Map.of(
                    "status", step.getStatus(),
                    "stepId", step.getId());
        } finally {
            runGuard.release(handle);
        }
    }

    /**
     * **按批重发**（#142 / ADR-0017 决策 7）：一次点击 = 一次请求。
     *
     * <p>{@code id} 是那一批的**摘要行**（{@code message_id = batch:<批号>}）的消息步 id——整份请求与
     * 整份响应都只在它身上（决策 6），所以重发从它取报文；同批每条消息在新运行里各记一行，
     * 旧的那一批标 {@code REDELIVERED}。</p>
     *
     * <p>#146：与断点续跑同一口径——已有运行在跑时拒绝，不排队。</p>
     */
    @PostMapping("/batches/{id}/redeliver")
    public Map<String, Object> redeliverBatch(@PathVariable Long id) {
        JobRunGuard.Handle handle = acquireForRedeliver(id);
        try {
            JobRun run = messageHistoryService.redeliverBatch(id);
            return Map.of(
                    "status", run.getStatus(),
                    "runId", run.getId());
        } finally {
            runGuard.release(handle);
        }
    }

    /**
     * 取重投任务的占用；拿不到就抛 409 + {@code JOB_ALREADY_RUNNING}（与手动执行同一个业务码）。
     *
     * <p>任务标识取不到时返回 {@code null} 而不抛：那属于「这条消息步本身有问题」，交给重投逻辑按它
     * 原有的口径报错（不存在 / 未启用 / 仅失败步可重投），别在这里抢先改判。</p>
     */
    private JobRunGuard.Handle acquireForRedeliver(Long stepId) {
        Long jobId = messageHistoryService.jobIdOfStep(stepId);
        if (jobId == null) {
            return null;
        }
        JobRunGuard.Handle handle = runGuard.tryAcquire(jobId, JobRunGuard.ORIGIN_REDELIVER);
        if (handle == null) {
            LOG.info("任务已有运行，跳过本次重投 job={} step={}", jobId, stepId);
            throw BusinessException.of(BusinessCode.JOB_ALREADY_RUNNING,
                    BusinessCode.JOB_ALREADY_RUNNING.defaultMessage());
        }
        return handle;
    }

    private int safePage(int page) {
        return Math.max(page, 1);
    }

    /** 列表接口一页的上限：整行（带两个 TEXT 快照）读回来，100 条已经不小。 */
    private int safeSize(int size) {
        return safeSize(size, LIST_MAX_SIZE);
    }

    private int safeSize(int size, int max) {
        return Math.min(Math.max(size, 1), max);
    }
}
