package com.example.edgegateway.web;

import com.example.edgegateway.service.ApiKeyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.SiteMapper;
import com.example.edgegateway.model.Site;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

/** 站点管理端点：API Key 生成/重置（明文只返回一次，库中存哈希）。 */
@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final ApiKeyService apiKeyService;
    private final SiteMapper siteMapper;

    public SiteController(ApiKeyService apiKeyService, SiteMapper siteMapper) {
        this.apiKeyService = apiKeyService;
        this.siteMapper = siteMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return siteMapper.selectList(new LambdaQueryWrapper<Site>().orderByAsc(Site::getId)).stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "name", s.getName(),
                        "timezone", s.getTimezone() == null ? "" : s.getTimezone(),
                        "hasApiKey", s.getApiKey() != null && !s.getApiKey().isBlank(),
                        "suggester", s.getSuggester() == null ? "" : s.getSuggester()))
                .toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Site site = siteMapper.selectById(id);
        if (site == null) {
            throw new java.util.NoSuchElementException("站点不存在: " + id);
        }
        return Map.<String, Object>of(
                "id", site.getId(),
                "name", site.getName(),
                "timezone", site.getTimezone() == null ? "" : site.getTimezone(),
                "hasApiKey", site.getApiKey() != null && !site.getApiKey().isBlank(),
                "suggester", site.getSuggester() == null ? "" : site.getSuggester());
    }

    @PostMapping("/{id}/api-key")
    public Map<String, Object> generateApiKey(@PathVariable Long id) {
        return Map.of("apiKey", apiKeyService.generate(id));
    }
}
