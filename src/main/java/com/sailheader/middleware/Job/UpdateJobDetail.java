package com.sailheader.middleware.Job;

import lombok.extern.slf4j.Slf4j;
import org.quartz.*;

@Slf4j
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
public class UpdateJobDetail implements Job {

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();

    }
}
