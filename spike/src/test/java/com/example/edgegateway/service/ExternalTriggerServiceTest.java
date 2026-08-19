package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.JobView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalTriggerServiceTest {

    private JobMapper jobMapper;
    private ApiKeyService apiKeyService;
    private JobExecutionService jobExecutionService;
    private ExternalTriggerService service;

    @BeforeEach
    void setUp() {
        jobMapper = mock(JobMapper.class);
        apiKeyService = mock(ApiKeyService.class);
        jobExecutionService = mock(JobExecutionService.class);
        service = new ExternalTriggerService(jobMapper, apiKeyService, jobExecutionService);
    }

    private Job job(Long id, Long siteId) {
        Job job = new Job();
        job.setId(id);
        job.setSiteId(siteId);
        return job;
    }

    @Test
    void triggersWhenApiKeyMatches() {
        Job job = job(1L, 5L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(apiKeyService.matches(5L, "secret-key")).thenReturn(true);
        Job executed = job(1L, 5L);
        executed.setStatus(1);
        when(jobExecutionService.execute(1L, Map.of())).thenReturn(executed);

        JobView view = service.trigger(1L, "secret-key", Map.of());

        assertThat(view.id()).isEqualTo(1L);
        verify(jobExecutionService).execute(1L, Map.of());
    }

    @Test
    void rejectsMissingKeyWithoutExecuting() {
        Job job = job(1L, 5L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(apiKeyService.matches(5L, null)).thenReturn(false);

        assertThatThrownBy(() -> service.trigger(1L, null, Map.of()))
                .isInstanceOf(BusinessException.class);
        verify(jobExecutionService, never()).execute(any(), any());
    }

    @Test
    void rejectsWrongKeyForTheSite() {
        Job job = job(1L, 5L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(apiKeyService.matches(5L, "wrong")).thenReturn(false);

        assertThatThrownBy(() -> service.trigger(1L, "wrong", Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsWhenJobMissing() {
        when(jobMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.trigger(99L, "any", Map.of()))
                .isInstanceOf(NoSuchElementException.class);
    }
}
