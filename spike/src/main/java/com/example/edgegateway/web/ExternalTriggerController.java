package com.example.edgegateway.web;

import com.example.edgegateway.service.ExternalTriggerService;
import com.example.edgegateway.web.dto.JobView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 外部触发端点：带站点 API Key（X-API-Key）执行任务。 */
@RestController
@RequestMapping("/api/external")
public class ExternalTriggerController {

    private final ExternalTriggerService triggerService;

    public ExternalTriggerController(ExternalTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @PostMapping("/jobs/{id}/trigger")
    public JobView trigger(
            @PathVariable Long id,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody(required = false) Map<String, Object> input) {
        return triggerService.trigger(id, apiKey, input);
    }
}
