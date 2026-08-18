package com.example.edgegateway.service;

import com.example.edgegateway.mapper.SiteMapper;
import com.example.edgegateway.model.Site;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private final SiteMapper siteMapper = mock(SiteMapper.class);
    private final ApiKeyService service = new ApiKeyService(siteMapper);

    @Test
    void generateStoresHashAndReturnsRawOnce() {
        Site site = new Site();
        site.setId(1L);
        when(siteMapper.selectById(1L)).thenReturn(site);

        String raw = service.generate(1L);

        assertThat(raw).isNotBlank();
        assertThat(site.getApiKey()).isNotEqualTo(raw);
        assertThat(site.getApiKey()).matches("[0-9a-f]{64}"); // SHA-256 hex
        assertThat(site.getApiKey()).doesNotContain(raw);
        verify(siteMapper).updateById(site);
    }

    @Test
    void generateThrowsWhenSiteMissing() {
        when(siteMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.generate(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void matchesOnlyTheGeneratedKey() {
        Site site = new Site();
        site.setId(1L);
        when(siteMapper.selectById(1L)).thenReturn(site);
        String raw = service.generate(1L);

        assertThat(service.matches(1L, raw)).isTrue();
        assertThat(service.matches(1L, raw + "x")).isFalse();
        assertThat(service.matches(1L, null)).isFalse();
        assertThat(service.matches(1L, " ")).isFalse();
    }

    @Test
    void matchesFalseWhenSiteOrKeyAbsent() {
        when(siteMapper.selectById(2L)).thenReturn(new Site());
        assertThat(service.matches(2L, "any")).isFalse(); // apiKey 为 null
        assertThat(service.matches(null, "any")).isFalse();
        assertThat(service.matches(99L, "any")).isFalse();
    }
}
