package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 手动执行：把任务输入通过 direct 端点送入生成的路由，
 * 同步等待执行结果并把最近一次状态/时间/错误写回任务。
 */
@Service
public class JobExecutionService {

    private final JobMapper jobMapper;
    private final CamelContext camelContext;

    public JobExecutionService(JobMapper jobMapper, CamelContext camelContext) {
        this.jobMapper = jobMapper;
        this.camelContext = camelContext;
    }

    public Job execute(Long jobId, Map<String, Object> input) {
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new NoSuchElementException("任务不存在: " + jobId);
        }
        if (job.getStatus() == null || job.getStatus() != 1) {
            throw new IllegalArgumentException("任务未启用，无法执行");
        }
        String endpoint = "direct:job-" + jobId;
        try (ProducerTemplate producer = camelContext.createProducerTemplate()) {
            producer.requestBody(endpoint, input);
            job.setLastRunStatus("SUCCESS");
            job.setLastError(null);
        } catch (Exception e) {
            job.setLastRunStatus("FAILED");
            job.setLastError(e.getMessage());
        }
        job.setLastRunAt(LocalDateTime.now());
        jobMapper.updateById(job);
        return job;
    }
}
