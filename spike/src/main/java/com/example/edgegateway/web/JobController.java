package com.example.edgegateway.web;

import com.example.edgegateway.service.ActionService;
import com.example.edgegateway.service.JobService;
import com.example.edgegateway.web.dto.ActionRequest;
import com.example.edgegateway.web.dto.ActionView;
import com.example.edgegateway.web.dto.JobRequest;
import com.example.edgegateway.web.dto.JobStatusRequest;
import com.example.edgegateway.web.dto.JobView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
/**
 * 任务 REST 接口：任务 CRUD + 启停；动作绑定在任务下（/api/jobs/{jobId}/actions）。
 * 错误映射由 ApiExceptionHandler 统一处理：400 校验失败 / 404 不存在 / 409 重名。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final ActionService actionService;

    public JobController(JobService jobService, ActionService actionService) {
        this.jobService = jobService;
        this.actionService = actionService;
    }

    @PostMapping
    public ResponseEntity<JobView> create(@RequestBody JobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(JobView.from(jobService.create(request)));
    }

    @PutMapping("/{id}")
    public JobView update(@PathVariable Long id, @RequestBody JobRequest request) {
        return JobView.from(jobService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        jobService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable Long id) {
        return JobView.from(jobService.get(id));
    }

    @GetMapping
    public List<JobView> list(@RequestParam(required = false) String name,
                              @RequestParam(required = false) Integer status) {
        return jobService.list(name, status).stream().map(JobView::from).toList();
    }

    @PutMapping("/{id}/status")
    public JobView setStatus(@PathVariable Long id, @RequestBody JobStatusRequest request) {
        return JobView.from(jobService.setStatus(id, request.status()));
    }

    @PostMapping("/{id}/actions")
    public ResponseEntity<ActionView> addAction(@PathVariable Long id, @RequestBody ActionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ActionView.from(actionService.add(id, request)));
    }

    @GetMapping("/{id}/actions")
    public List<ActionView> listActions(@PathVariable Long id) {
        return actionService.list(id).stream().map(ActionView::from).toList();
    }

}
