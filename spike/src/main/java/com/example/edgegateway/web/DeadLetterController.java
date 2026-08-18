package com.example.edgegateway.web;

import com.example.edgegateway.model.DeadLetter;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.service.DeadLetterService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 死信查看 REST：列表（按 jobId/status 过滤）+ 详情。 */
@RestController
@RequestMapping("/api/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public List<DeadLetter> list(@RequestParam(required = false) Long jobId,
                                 @RequestParam(required = false) String status) {
        return deadLetterService.list(jobId, status);
    }

    @GetMapping("/{id}")
    public DeadLetter get(@PathVariable Long id) {
        return deadLetterService.get(id);
    }

    /** 重投死信输入到任务；成功后死信状态改为 REDELIVERED。 */
    @PostMapping("/{id}/redeliver")
    public Map<String, Object> redeliver(@PathVariable Long id) {
        Job job = deadLetterService.redeliver(id);
        return Map.of(
                "lastRunStatus", job.getLastRunStatus() == null ? "SUCCESS" : job.getLastRunStatus(),
                "lastError", job.getLastError() == null ? "" : job.getLastError(),
                "deadLetterStatus", "REDELIVERED");
    }
}
