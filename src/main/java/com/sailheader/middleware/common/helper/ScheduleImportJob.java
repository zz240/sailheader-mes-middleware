package com.sailheader.middleware.common.helper;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.job.UpdateJobDetail;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.model.ImportJob;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.sailheader.middleware.job.UpdateJobDetail.KEY_IMPORT_JOB;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

@Slf4j
@Service
public class ScheduleImportJob {

    /**
     * 执行定时同步任务
     * @param importJob
     * @return
     * @throws SchedulerException
     */
    public Boolean executeScheduleImportJob(ImportJob importJob) throws SchedulerException {
        boolean result = false;
        SchedulerFactory schedulerFactory = new StdSchedulerFactory();
        Scheduler scheduler = schedulerFactory.getScheduler();
        if (importJob != null && GeneralStatus.ACTIVE.getValue().equals(importJob.getStatus()) && StringUtils.hasText(importJob.getCronExpression())){
            JobKey jobKey = JobKey.jobKey(importJob.getName(), Scheduler.DEFAULT_GROUP);
            TriggerKey triggerKey = TriggerKey.triggerKey(importJob.getName(), Scheduler.DEFAULT_GROUP);
            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put(KEY_IMPORT_JOB, JSON.toJSONString(importJob));
            JobDetail job = newJob(UpdateJobDetail.class).setJobData(jobDataMap)
                    .withIdentity(jobKey).storeDurably().build();
            Trigger trigger = newTrigger().withSchedule(CronScheduleBuilder.cronSchedule(importJob.getCronExpression()))
                    .withIdentity(triggerKey)
                    .build();
            result = scheduler.scheduleJob(job, trigger) != null;
            scheduler.start();
//            log.info("同步任务"+importJob.getName()+"成功！");
        }
        return result;
    }

    /**
     * 移除指定名称的定时任务
     * @param name
     * @return
     * @throws SchedulerException
     */
    public Boolean removeScheduleImportJob(String name)throws SchedulerException{
        boolean result = false;
        TriggerKey triggerKey = TriggerKey.triggerKey(name, Scheduler.DEFAULT_GROUP);
        JobKey jobKey = JobKey.jobKey(name, Scheduler.DEFAULT_GROUP);
        try {
            SchedulerFactory schedulerFactory = new StdSchedulerFactory();
            Scheduler scheduler = schedulerFactory.getScheduler();
            scheduler.pauseTrigger(triggerKey);// 停止触发器
            scheduler.unscheduleJob(triggerKey);// 移除触发器
            scheduler.deleteJob(jobKey);// 删除任务
        }catch (Exception e){
            e.printStackTrace();
        }
        return result;
    }


}
