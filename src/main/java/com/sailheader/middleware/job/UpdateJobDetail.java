package com.sailheader.middleware.job;


import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.rest.JobController;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
@Service
public class UpdateJobDetail implements Job {
    @Autowired
    JobController jobController;

    public final static String KEY_IMPORT_JOB = "job";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();
            String jsonString = jobDataMap.getString(KEY_IMPORT_JOB);
            if (StringUtils.hasText(jsonString)) {
                com.sailheader.middleware.model.Job job = JSON.parseObject(jsonString, com.sailheader.middleware.model.Job.class);
                log.info("触发-定时同步任务：" + job.getName());
                jobController.ManualTrigger(job.getId());
            }else{
                log.error("执行定时任务异常！");
            }
        } catch (Exception e) {
            log.error("执行定时任务异常！");
        }
    }
}
