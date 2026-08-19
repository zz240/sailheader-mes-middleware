package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.JobRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobServiceTest {

    private JobMapper mapper;
    private JobService service;

    @BeforeEach
    void setUp() {
        mapper = mock(JobMapper.class);
        service = new JobService(mapper);
    }

    private JobRequest request(String name) {
        return new JobRequest(name, 1L, 0, "0 0/30 * * * ?", "Asia/Shanghai",
                3, 1000L, "备注");
    }

    @Test
    void createsValidJob() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(Job.class))).thenAnswer(inv -> {
            inv.getArgument(0, Job.class).setId(1L);
            return 1;
        });

        Job saved = service.create(request("po-sync"));

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getName()).isEqualTo("po-sync");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create(request("")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateNameInSite() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("dup")))
                .isInstanceOf(BusinessException.class);
    }


    @Test
    void setsStatus() {
        Job job = new Job();
        job.setId(1L);
        job.setName("j");
        job.setStatus(0);
        when(mapper.selectById(1L)).thenReturn(job);

        Job updated = service.setStatus(1L, 1);

        assertThat(updated.getStatus()).isEqualTo(1);
    }
}
