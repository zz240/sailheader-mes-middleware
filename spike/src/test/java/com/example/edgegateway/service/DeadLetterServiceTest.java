package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DeadLetterMapper;
import com.example.edgegateway.model.DeadLetter;
import com.example.edgegateway.model.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
        when(mapper.selectList(any())).thenReturn(List.of(deadLetter));

        List<DeadLetter> list = service.list(7L, "FAILED");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getStatus()).isEqualTo("FAILED");
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
