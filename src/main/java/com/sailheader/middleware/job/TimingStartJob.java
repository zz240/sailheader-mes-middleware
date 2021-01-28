package com.sailheader.middleware.job;

import com.sailheader.middleware.common.helper.ScheduleImportJob;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.service.JobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class TimingStartJob implements ApplicationRunner {
    @Autowired
    private JobService jobService;
    @Autowired
    private ScheduleImportJob scheduleImportJob;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Job> jobs = jobService.findActiveTimedTask();
        if (jobs.size() > 0){
            jobs.forEach(job -> {
                try {
                    scheduleImportJob.executeScheduleImportJob(job);
                } catch (Exception e) {
                    log.info("定时执行任务 "+job.getName()+"出错："+e.getMessage());
                }
            });
        }
    }
}
