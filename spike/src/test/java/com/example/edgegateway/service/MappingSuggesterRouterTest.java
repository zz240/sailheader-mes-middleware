package com.example.edgegateway.service;

import com.example.edgegateway.config.EdgeMappingProperties;
import com.example.edgegateway.mapper.SiteMapper;
import com.example.edgegateway.mapping.suggest.LlmMappingSuggester;
import com.example.edgegateway.mapping.suggest.MappingSuggester;
import com.example.edgegateway.mapping.suggest.RuleMappingSuggester;
import com.example.edgegateway.model.Site;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MappingSuggesterRouterTest {

    private final SiteMapper siteMapper = mock(SiteMapper.class);
    private final RuleMappingSuggester rule = new RuleMappingSuggester();
    private final LlmMappingSuggester llm = mock(LlmMappingSuggester.class);

    private MappingSuggesterRouter router(String globalMode) {
        EdgeMappingProperties properties = new EdgeMappingProperties();
        properties.setSuggester(globalMode);
        return new MappingSuggesterRouter(properties, siteMapper, rule, llm);
    }

    @Test
    void globalRuleIsDefault() {
        MappingSuggester suggester = router("rule").forSite(null);
        assertThat(suggester).isSameAs(rule);
    }

    @Test
    void globalLlmSelectsLlm() {
        MappingSuggester suggester = router("llm").forSite(null);
        assertThat(suggester).isSameAs(llm);
    }

    @Test
    void siteOverrideTakesPrecedence() {
        Site site = new Site();
        site.setSuggester("llm");
        when(siteMapper.selectById(1L)).thenReturn(site);

        MappingSuggester suggester = router("rule").forSite(1L);
        assertThat(suggester).isSameAs(llm);
    }

    @Test
    void blankSiteOverrideFallsBackToGlobal() {
        Site site = new Site();
        site.setSuggester(" ");
        when(siteMapper.selectById(1L)).thenReturn(site);

        MappingSuggester suggester = router("rule").forSite(1L);
        assertThat(suggester).isSameAs(rule);
    }
}
