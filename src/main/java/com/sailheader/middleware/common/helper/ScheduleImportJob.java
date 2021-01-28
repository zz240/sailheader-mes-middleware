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
     *
     * @param job
     * @return
     * @throws SchedulerException
     */
    public Boolean executeScheduleImportJob(Job job) throws SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        String msg;
        if (job == null) {
            msg = "未发现任务";
            log.error(msg);
            return false;
        }
        if (job.getStatus() == null || !job.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
            return false;
        }
        if (!StringUtils.hasText(job.getCrontab())) {
            msg = "普通任务";
            log.info(msg);
            return true;
        }
        JobKey jobKey = JobKey.jobKey(job.getName(), Scheduler.DEFAULT_GROUP);
        TriggerKey triggerKey = TriggerKey.triggerKey(job.getName(), Scheduler.DEFAULT_GROUP);
        jobDataMap.put(KEY_IMPORT_JOB, JSON.toJSONString(job));

        //任务
        JobDetail jobDetail = newJob(UpdateJobDetail.class).setJobData(jobDataMap).withIdentity(jobKey).storeDurably().build();
        //触发器
        Trigger trigger = newTrigger().withSchedule(CronScheduleBuilder.cronSchedule(job.getCrontab()))
                .withIdentity(triggerKey)
                .build();
        //调度器
        boolean result = scheduler.scheduleJob(jobDetail, trigger) != null;
        scheduler.start();
        return result;
    }

    /**
     * 移除指定名称的定时任务
     *
     * @param name
     * @return
     * @throws SchedulerException
     */
    public Boolean removeScheduleImportJob(String name) throws SchedulerException {
        boolean result = false;
        TriggerKey triggerKey = TriggerKey.triggerKey(name, Scheduler.DEFAULT_GROUP);
        JobKey jobKey = JobKey.jobKey(name, Scheduler.DEFAULT_GROUP);
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        // 停止触发器
        scheduler.pauseTrigger(triggerKey);
        // 移除触发器
        scheduler.unscheduleJob(triggerKey);
        // 删除任务
        scheduler.deleteJob(jobKey);
        result = true;
        return result;
    }


    /**
     * 修改一个任务的触发时间
     *
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
