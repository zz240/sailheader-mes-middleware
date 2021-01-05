package com.sailheader.middleware.test;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MyScheduler {

    @RequestMapping("/testQuartz")
    public void test() throws SchedulerException {
        // 1、创建调度器
        StdSchedulerFactory stdSchedulerFactory = new StdSchedulerFactory();
        Scheduler scheduler = stdSchedulerFactory.getScheduler();

        // 2、创建JobDetail实例，并与PrintWordsJob类绑定
        JobDetail jobDetail = JobBuilder.newJob(PrintWordsJob.class).build();

        // 3、构建Trigger实例,每隔3s执行一次
        CronTrigger cronTrigger = TriggerBuilder.newTrigger().startNow().withSchedule(CronScheduleBuilder.cronSchedule("0/3 * * * * ? *")).build();

        // 4、执行
        scheduler.scheduleJob(jobDetail,cronTrigger);
        System.out.println("start!");
        scheduler.start();

    }
}


