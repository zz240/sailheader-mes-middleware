package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.service.JobService;
import com.sun.org.apache.bcel.internal.generic.IF_ACMPEQ;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@RestController
public class JobController {
    @Autowired
    private JobService jobService;

    @PostMapping("/wm/api/job/create")
    public OperationResult createJob(@RequestBody Job job){
        int i = jobService.insertSelective(job);
        if (i == 1){
            return new OperationResult(job.getName()).setMessage("插入成功").setStateCode(StateCode.ADD_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("插入异常").setStateCode(StateCode.ADD_FAIL.getValue());
    }

    @DeleteMapping("/wm/api/job/delete/{id}")
    public OperationResult deleteDataSource(@PathVariable Long id){
        Job job = jobService.selectJobById(id);
        if (job != null && !job.getStatus().equals(GeneralStatus.DELETED)){
            job.setStatus(GeneralStatus.DELETED.getIntLabel());
            jobService.updateJobSelective(job);
            return new OperationResult(id).setMessage("删除成功").setStateCode(StateCode.DELETE_SUCCESS.getValue());
        }
        return new OperationResult().setMessage("删除失败,已删除或不存在！").setStateCode(StateCode.DELETE_FAIL.getValue());
    }

    @GetMapping("/wm/api/job/query/{id}")
    public OperationResult findDataSourceById(@PathVariable Long id){
        Job job = jobService.selectJobById(id);
        if (job != null && !job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            return new OperationResult().setData(job).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    @GetMapping("/wm/api/job/query/all")
    public OperationResult findAllDataSources(){
        return new OperationResult().setData(jobService.findJobByStatus(GeneralStatus.ACTIVE.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }


    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/wm/api/job/update/{id}")
    public OperationResult updateJob(@PathVariable Long id,@RequestBody Job job){
        Job jobById = jobService.selectJobById(id);
        if (jobById != null && jobById.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())){
            jobById.setName(job.getName());
            jobById.setCrontab(job.getCrontab());
            jobById.setRemarks(job.getRemarks());
            jobById.setStatus(job.getStatus());
            jobById.setModifiedBy(job.getModifiedBy());
            jobById.setModifiedOn(new Date());
            jobService.updateJobSelective(jobById);
            return new OperationResult(jobById).setMessage("更新成功！").setStateCode(StateCode.EDIT_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("更新失败！").setStateCode(StateCode.EDIT_FAIL.getValue());
    }


}
