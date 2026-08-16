package com.example.edgegateway.web;

import com.example.edgegateway.service.ActionService;
import com.example.edgegateway.web.dto.ActionRequest;
import com.example.edgegateway.web.dto.ActionView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动作 REST 接口：按动作 id 更新（含调整序号）、删除、查询。
 * 绑定动作（新增）和按任务列动作仍走 /api/jobs/{jobId}/actions。
 * 错误映射由 ApiExceptionHandler 统一处理。
 */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private final ActionService actionService;

    public ActionController(ActionService actionService) {
        this.actionService = actionService;
    }

    @PutMapping("/{id}")
    public ActionView update(@PathVariable Long id, @RequestBody ActionRequest request) {
        return ActionView.from(actionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        actionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ActionView get(@PathVariable Long id) {
        return ActionView.from(actionService.get(id));
    }

}
