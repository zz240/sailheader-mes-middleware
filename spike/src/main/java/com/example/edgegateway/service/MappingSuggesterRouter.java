package com.example.edgegateway.service;

import com.example.edgegateway.config.EdgeMappingProperties;
import com.example.edgegateway.mapper.SiteMapper;
import com.example.edgegateway.mapping.suggest.LlmMappingSuggester;
import com.example.edgegateway.mapping.suggest.MappingSuggester;
import com.example.edgegateway.mapping.suggest.RuleMappingSuggester;
import com.example.edgegateway.model.Site;
import org.springframework.stereotype.Component;

/**
 * 映射建议器路由：全局配置 edge.mapping.suggester=rule|llm，站点级可在 site.suggester 覆盖。
 */
@Component
public class MappingSuggesterRouter {

    private final EdgeMappingProperties properties;
    private final SiteMapper siteMapper;
    private final RuleMappingSuggester rule;
    private final LlmMappingSuggester llm;

    public MappingSuggesterRouter(
            EdgeMappingProperties properties,
            SiteMapper siteMapper,
            RuleMappingSuggester rule,
            LlmMappingSuggester llm) {
        this.properties = properties;
        this.siteMapper = siteMapper;
        this.rule = rule;
        this.llm = llm;
    }

    public MappingSuggester forSite(Long siteId) {
        if (siteId != null) {
            Site site = siteMapper.selectById(siteId);
            if (site != null && site.getSuggester() != null && !site.getSuggester().isBlank()) {
                return "llm".equalsIgnoreCase(site.getSuggester()) ? llm : rule;
            }
        }
        return "llm".equalsIgnoreCase(properties.getSuggester()) ? llm : rule;
    }
}
