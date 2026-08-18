package com.example.edgegateway.web;

import com.example.edgegateway.service.ApiKeyService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 站点管理端点：API Key 生成/重置（明文只返回一次，库中存哈希）。 */
@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final ApiKeyService apiKeyService;

    public SiteController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping("/{id}/api-key")
    public Map<String, Object> generateApiKey(@PathVariable Long id) {
        return Map.of("apiKey", apiKeyService.generate(id));
    }
}
