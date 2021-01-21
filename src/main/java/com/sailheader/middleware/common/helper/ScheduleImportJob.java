package com.sailheader.middleware.common.helper;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.job.UpdateJobDetail;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.model.Job;
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
     * 触发定时同步任务
     * @param job
     * @return
     * @throws SchedulerException
     */
    public Boolean executeScheduleImportJob(Job job) throws SchedulerException {
        boolean result = false;
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        if (job != null && GeneralStatus.ACTIVE.getValue().equals(job.getStatus()) && StringUtils.hasText(job.getCrontab())){

            JobKey jobKey = JobKey.jobKey(job.getName(), Scheduler.DEFAULT_GROUP);
            TriggerKey triggerKey = TriggerKey.triggerKey(job.getName(), Scheduler.DEFAULT_GROUP);
            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put(KEY_IMPORT_JOB, JSON.toJSONString(job));

            //任务
            JobDetail jobDetail = newJob(UpdateJobDetail.class).setJobData(jobDataMap)
                    .withIdentity(jobKey).storeDurably().build();
            System.out.println("jobDetail :"+jobDetail);
            //触发器
            Trigger trigger = newTrigger().withSchedule(CronScheduleBuilder.cronSchedule(job.getCrontab()))
                    .withIdentity(triggerKey)
                    .build();
            result = scheduler.scheduleJob(jobDetail, trigger) != null;
            scheduler.start();
        }else {
            log.error("触发定时任务异常！");
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
            Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
            scheduler.pauseTrigger(triggerKey);// 停止触发器
            scheduler.unscheduleJob(triggerKey);// 移除触发器
            scheduler.deleteJob(jobKey);// 删除任务
            result = true;
        }catch (Exception e){
            log.error("定时任务删除失败");
        }
        return result;
    }


    /**
     * 修改一个任务的触发时间
     * @param jobName
     * @param cron
     */
    public void modifyJobTime(String jobName, String cron) {
        SchedulerFactory schedulerFactory = new StdSchedulerFactory();

        TriggerKey triggerKey = TriggerKey.triggerKey(jobName, Scheduler.DEFAULT_GROUP);

        try {
            Scheduler sched = schedulerFactory.getScheduler();
            CronTrigger trigger = (CronTrigger) sched.getTrigger(triggerKey);
            if (trigger == null) {
                return;
            }
            String oldTime = trigger.getCronExpression();
            if (!oldTime.equalsIgnoreCase(cron)) {
                CronScheduleBuilder scheduleBuilder = CronScheduleBuilder.cronSchedule(cron);
                // 按新的cronExpression表达式重新构建trigger
                trigger = trigger.getTriggerBuilder().withIdentity(triggerKey).withSchedule(scheduleBuilder).build();
                // 按新的trigger重新设置job执行
                sched.rescheduleJob(triggerKey, trigger);
            }
        } catch (Exception e) {
            log.error("修改定时任务失败");
        }
    }



}
