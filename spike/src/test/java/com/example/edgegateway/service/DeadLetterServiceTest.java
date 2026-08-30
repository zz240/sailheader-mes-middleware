package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DeadLetterMapper;
import com.example.edgegateway.model.DeadLetter;
import com.example.edgegateway.model.Job;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.converter.stream.InputStreamCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLetterServiceTest {

    private final DeadLetterMapper mapper = mock(DeadLetterMapper.class);
    private final JobExecutionService jobExecutionService = mock(JobExecutionService.class);
    private final DeadLetterService service =
            new DeadLetterService(mapper, new ObjectMapper(), jobExecutionService);

    @Test
    void recordsFailureWithInputAndError() {
        Exception failure = new IllegalStateException("HTTP 500");
        service.record(7L, 3, Map.of("order_no", "PO-1"), failure);

        verify(mapper).insert(any(DeadLetter.class));
    }

    @Test
    void listFiltersByJobAndStatus() {
        DeadLetter deadLetter = new DeadLetter();
        deadLetter.setId(1L);
        deadLetter.setStatus("FAILED");
        Page<DeadLetter> page = new Page<>(1, 20);
        page.setRecords(List.of(deadLetter));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> result = service.list(7L, "FAILED", 1, 20);

        assertThat(result.get("total")).isEqualTo(1L);
        assertThat((List<?>) result.get("records")).hasSize(1);
    }

    @Test
    void recordConvertsStreamCacheInputToJsonString() throws Exception {
        String json = "{\"orderNo\":\"MO-1\",\"material\":\"M-9\",\"qty\":10}";
        try (InputStreamCache cache = new InputStreamCache(json.getBytes(StandardCharsets.UTF_8))) {
            service.record(7L, 3, cache, new IllegalStateException("boom"));
        }
        ArgumentCaptor<DeadLetter> captor = ArgumentCaptor.forClass(DeadLetter.class);
        verify(mapper).insert(captor.capture());

        assertThat(captor.getValue().getInputJson()).isEqualTo(json);
        assertThat(captor.getValue().getInputJson()).doesNotContain("InputStreamCache");
    }

    @Test
    void redeliverRejectsNonJsonInput() {
        DeadLetter deadLetter = new DeadLetter();
        deadLetter.setId(1L);
        deadLetter.setJobId(7L);
        deadLetter.setInputJson("org.apache.camel.converter.stream.InputStreamCache@123");
        deadLetter.setStatus("FAILED");
        when(mapper.selectById(1L)).thenReturn(deadLetter);

        assertThatThrownBy(() -> service.redeliver(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 JSON 对象");
    }

    @Test
    void getThrowsWhenMissing() {
        when(mapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void redeliverMarksSuccessAsRedelivered() {
        DeadLetter deadLetter = new DeadLetter();
        deadLetter.setId(1L);
        deadLetter.setJobId(7L);
        deadLetter.setInputJson("{\"orderNo\":\"MO-1\",\"material\":\"M-9\",\"qty\":10}");
        deadLetter.setStatus("FAILED");
        when(mapper.selectById(1L)).thenReturn(deadLetter);
        Job job = new Job();
        job.setId(7L);
        job.setLastRunStatus("SUCCESS");
        when(jobExecutionService.execute(7L, Map.of("orderNo", "MO-1", "material", "M-9", "qty", 10)))
                .thenReturn(job);

        Job result = service.redeliver(1L);

        assertThat(result.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(deadLetter.getStatus()).isEqualTo("REDELIVERED");
        org.mockito.Mockito.verify(mapper).updateById(deadLetter);
    }

    @Test
    void redeliverKeepsFailedStatusWhenExecutionFails() {
        DeadLetter deadLetter = new DeadLetter();
        deadLetter.setId(1L);
        deadLetter.setJobId(7L);
        deadLetter.setInputJson("{\"orderNo\":\"MO-1\"}");
        deadLetter.setStatus("FAILED");
        when(mapper.selectById(1L)).thenReturn(deadLetter);
        Job job = new Job();
        job.setLastRunStatus("FAILED");
        job.setLastError("Connection refused");
        when(jobExecutionService.execute(7L, Map.of("orderNo", "MO-1"))).thenReturn(job);

        assertThatThrownBy(() -> service.redeliver(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重投失败");
        assertThat(deadLetter.getStatus()).isEqualTo("FAILED");
    }
}
