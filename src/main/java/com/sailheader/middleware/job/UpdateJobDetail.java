package com.sailheader.middleware.job;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.model.ImportJob;
import com.sailheader.middleware.service.DataUpdateService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
@Service
public class UpdateJobDetail implements Job {
    public final static String KEY_IMPORT_JOB = "importJob";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        DataUpdateService dataUpdateService = new DataUpdateService();
        ImportJob importJob = null;
        try {
            JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();
            String jsonString = jobDataMap.getString(KEY_IMPORT_JOB);
            if (StringUtils.hasText(jsonString)) {
                importJob = JSON.parseObject(jsonString, ImportJob.class);
                log.info("触发-定时同步任务：" + importJob.getName());
                dataUpdateService.executeImportJobClassConfigRestApi(importJob);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
