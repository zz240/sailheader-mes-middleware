package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobExecutionServiceTest {

    private final JobMapper jobMapper = mock(JobMapper.class);
    private final CamelContext camelContext = mock(CamelContext.class);
    private final JobExecutionService service = new JobExecutionService(jobMapper, camelContext);

    @Test
    void executeMarksSuccess() throws Exception {
        Job job = enabledJob();
        when(jobMapper.selectById(1L)).thenReturn(job);
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(camelContext.createProducerTemplate()).thenReturn(producer);

        Job result = service.execute(1L, Map.of("orderNo", "PO-1"));

        assertThat(result.getLastRunStatus()).isEqualTo("SUCCESS");
        verify(jobMapper).updateById(job);
    }

    @Test
    void executeMarksFailedOnException() throws Exception {
        Job job = enabledJob();
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(camelContext.createProducerTemplate()).thenThrow(new RuntimeException("boom"));

        Job result = service.execute(1L, null);

        assertThat(result.getLastRunStatus()).isEqualTo("FAILED");
        assertThat(result.getLastError()).contains("boom");
    }

    @Test
    void rejectsDisabledJob() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(0);
        when(jobMapper.selectById(1L)).thenReturn(job);

        assertThatThrownBy(() -> service.execute(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未启用");
    }

    private Job enabledJob() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        return job;
    }
}
