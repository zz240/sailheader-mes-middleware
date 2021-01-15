package com.sailheader.middleware.job;


import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.rest.JobController;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
@Service
public class UpdateJobDetail implements Job {
    public final static String KEY_IMPORT_JOB = "job";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobController jobController = new JobController();
        try {
            JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();
            String jsonString = jobDataMap.getString(KEY_IMPORT_JOB);
            if (StringUtils.hasText(jsonString)) {
                com.sailheader.middleware.model.Job job = JSON.parseObject(jsonString, com.sailheader.middleware.model.Job.class);
                log.info("触发-定时同步任务：" + job.getName());
                jobController.ManualTrigger(job.getId());
            }
        } catch (Exception e) {
            log.info("执行定时任务异常！");
        }
    }
}
