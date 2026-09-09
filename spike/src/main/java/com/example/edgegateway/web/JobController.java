package com.example.edgegateway.web;

import com.example.edgegateway.service.ActionService;
import com.example.edgegateway.service.JobExecutionService;
import com.example.edgegateway.service.JobService;
import com.example.edgegateway.service.JobValidationService;
import com.example.edgegateway.service.RouteLifecycle;
import com.example.edgegateway.service.RouteRegistry;
import com.example.edgegateway.web.dto.ActionRequest;
import com.example.edgegateway.web.dto.ActionView;
import com.example.edgegateway.web.dto.JobRequest;
import com.example.edgegateway.web.dto.JobStatusRequest;
import com.example.edgegateway.web.dto.JobView;
import com.example.edgegateway.web.dto.ValidationResult;
import com.example.edgegateway.model.Job;
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
import java.util.Map;
/**
 * 任务 REST 接口：任务 CRUD + 启停；动作绑定在任务下（/api/jobs/{jobId}/actions）。
 * 错误映射由 ApiExceptionHandler 统一处理：400 校验失败 / 404 不存在 / 409 重名。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final ActionService actionService;
    private final RouteRegistry routeRegistry;
    private final RouteLifecycle routeLifecycle;
    private final JobExecutionService jobExecutionService;
    private final JobValidationService jobValidationService;

    public JobController(JobService jobService, ActionService actionService,
                         RouteRegistry routeRegistry, RouteLifecycle routeLifecycle,
                         JobExecutionService jobExecutionService,
                         JobValidationService jobValidationService) {
        this.jobService = jobService;
        this.actionService = actionService;
        this.routeRegistry = routeRegistry;
        this.routeLifecycle = routeLifecycle;
        this.jobExecutionService = jobExecutionService;
        this.jobValidationService = jobValidationService;
    }

    @PostMapping
    public ResponseEntity<JobView> create(@RequestBody JobRequest request) {
        Job job = jobService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(JobView.from(job));
    }

    @PutMapping("/{id}")
    public JobView update(@PathVariable Long id, @RequestBody JobRequest request) {
        // 配置变更不自动启停，统一由 /status 端点控制，避免"空动作就启用"的中间态
        return JobView.from(jobService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        routeRegistry.disable(id);
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
        Job job = jobService.setStatus(id, request.status());
        if (job.getStatus() == 1) {
            // 经 RouteLifecycle 启用：链不一致等配置问题会失败并自动停用 + 写 lastError（不留"已启用但无路由"的死任务）
            routeLifecycle.enable(id);
        } else {
            routeRegistry.disable(id);
        }
        return JobView.from(job);
    }

    @PostMapping("/{id}/execute")
    public JobView execute(@PathVariable Long id,
                           @RequestBody(required = false) Map<String, Object> input) {
        return JobView.from(jobExecutionService.execute(id, input));
    }

    @PostMapping("/{id}/actions")
    public ResponseEntity<ActionView> addAction(@PathVariable Long id, @RequestBody ActionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ActionView.from(actionService.add(id, request)));
    }

    @GetMapping("/{id}/actions")
    public List<ActionView> listActions(@PathVariable Long id) {
        return actionService.list(id).stream().map(ActionView::from).toList();
    }

    @PostMapping("/{id}/validate")
    public ValidationResult validate(@PathVariable Long id) {
        return jobValidationService.validate(id);
    }

}
