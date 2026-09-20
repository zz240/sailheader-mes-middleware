package com.example.edgegateway.web;

import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.service.MessageHistoryService;
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

    private final MessageHistoryService messageHistoryService;

    public HistoryController(MessageHistoryService messageHistoryService) {
        this.messageHistoryService = messageHistoryService;
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

    /** 从失败消息步断点续跑；成功后原消息步状态改为 REDELIVERED。 */
    @PostMapping("/steps/{id}/redeliver")
    public Map<String, Object> redeliver(@PathVariable Long id) {
        MessageStep step = messageHistoryService.redeliver(id);
        return Map.of(
                "status", step.getStatus(),
                "stepId", step.getId());
    }

    private int safePage(int page) {
        return Math.max(page, 1);
    }

    private int safeSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
