package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.helper.ScheduleImportJob;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.ImportJob;
import com.sailheader.middleware.service.ImportJobService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class ImportJobController {

    @Autowired
    private ImportJobService importJobService;
    @Autowired
    private ScheduleImportJob scheduleImportJob;


    /**
     * 查询所有同步任务
     *
     * @return
     */
    @GetMapping("/api/import/data/jobs")
    public OperationResult findAllImportJobs() {
        return new OperationResult().setData(importJobService.findImportJobByStatus(GeneralStatus.ACTIVE.getValue())).setMessage("查询成功").setStateCode(StateCode.QUERY_SUCCESS.getValue());
    }

    /**
     * 新增同步任务
     *
     * @param importJob
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/api/import/data/jobs")
    public OperationResult createImportJob(@RequestBody ImportJob importJob) {
        try {
            if (importJob != null && importJob.getName() != null) {
                ImportJob jobs = importJobService.findImportJobByName(importJob.getName());
                if (jobs != null) {
                    log.info("当前任务已存在！");
                    return new OperationResult().setData(null).setMessage("添加同步任务失败，已存在！").setStateCode(StateCode.ADD_FAIL.getValue());
                }
                importJob.setStatus(GeneralStatus.ACTIVE.getValue());
                importJobService.insertSelective(importJob);
                //执行同步任务
                if (!importJob.getCronExpression().isEmpty()) {
                    System.out.println("定时任务！");
                    boolean flag = scheduleImportJob.executeScheduleImportJob(importJobService.findImportJobByName(importJob.getName()));
                    if (flag == true) {
                        return new OperationResult().setData(importJob.getName()).setMessage("添加同步任务成功！").setStateCode(StateCode.ADD_SUCCESS.getValue());
                    }
                }
                return new OperationResult().setData(importJob.getName()).setMessage("添加任务成功！").setStateCode(StateCode.ADD_SUCCESS.getValue());
            }
        } catch (SchedulerException e) {
//            e.printStackTrace();
        }
        log.info("添加失败");
        return new OperationResult().setData(null).setMessage("添加同步任务失败").setStateCode(StateCode.ADD_FAIL.getValue());
    }

    /**
     * 根据id更新同步任务
     *
     * @param jobId 需要更新同步任务的id
     * @param job   同步对象任务
     * @return
     */
    @PutMapping("/api/import/data/job/{jobId}")
    public OperationResult updateImportJob(@PathVariable Long jobId, @RequestBody ImportJob job) {
        ImportJob importJob = importJobService.findImportJobById(jobId);
        if (importJob != null) {
            if (!importJob.getCronExpression().isEmpty()) {
                try {
                    scheduleImportJob.removeScheduleImportJob(importJob.getName());
                } catch (SchedulerException e) {
                    return new OperationResult().setData(null).setMessage("更新同步任务异常").setStateCode(StateCode.EDIT_FAIL.getValue());
                }
            }
            importJob.setRestUrl(job.getRestUrl());
            importJob.setName(job.getName());
            importJob.setCronExpression(job.getCronExpression());
            importJob.setTargetAddress(job.getTargetAddress());
            importJob.setRemarks(job.getRemarks());
            importJob.setEnabled(job.getEnabled());
            importJobService.updateByPrimaryKeySelective(importJob);
            if (!importJob.getCronExpression().isEmpty()) {
                System.out.println("定时任务！");
                try {
                    scheduleImportJob.executeScheduleImportJob(importJobService.findImportJobByName(importJob.getName()));
                } catch (SchedulerException e) {
                    return new OperationResult().setData(null).setMessage("更新同步任务异常").setStateCode(StateCode.EDIT_FAIL.getValue());
                }
            }
            return new OperationResult().setData(importJob.getName()).setMessage("更新同步任务成功").setStateCode(StateCode.EDIT_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("更新同步任务失败,查询不到任务！").setStateCode(StateCode.EDIT_FAIL.getValue());
    }

    /**
     * 根据id删除同步任务
     *
     * @param id 需要删除任务的id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @DeleteMapping("/api/import/data/job/{id}")
    public OperationResult deleteImportJob(@PathVariable Long id) {
        ImportJob job = importJobService.findImportJobById(id);
        if (job != null) {
            try {
                job.setStatus(GeneralStatus.DELETED.getValue());
                importJobService.updateByImportJob(job);
                scheduleImportJob.removeScheduleImportJob(job.getName());
                log.info("删除成功！");
                return new OperationResult().setData(job.getName()).setMessage("删除成功").setStateCode(StateCode.DELETE_SUCCESS.getValue());
            } catch (Exception e) {
                e.printStackTrace();
                return new OperationResult().setData(null).setMessage("删除失败").setStateCode(StateCode.DELETE_FAIL.getValue());
            }
        }
        log.info("删除失败,找不到对应的任务");
        return new OperationResult().setData(null).setMessage("删除失败").setStateCode(StateCode.DELETE_FAIL.getValue());
    }
}
