package com.sailheader.middleware.rest;


import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandler;
import com.sailheader.middleware.action.ActionHandlerAdapter;

import com.sailheader.middleware.chain.SingleLinerJobActionHandlerChain;
import com.sailheader.middleware.common.helper.ScheduleImportJob;
import com.sailheader.middleware.common.vo.OperationResult;

import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.service.JobService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Date;

import static com.sailheader.middleware.constant.ActionTypeEnum.remoteAccess;


@Slf4j
@RestController(value = "JobController")
public class JobController {

    @Autowired
    private JobService jobService;
    @Autowired
    private ScheduleImportJob scheduleImportJob;
    @Autowired
    private ApplicationContext applicationContext;


    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/mw/api/job/create")
    public OperationResult createJob(@RequestBody Job job) {
        int i = jobService.insertSelective(job);
        Job jobByName = jobService.selectJobByName(job.getName());
        if (i == 1) {
            if (jobByName.getStatus().equals(GeneralStatus.ACTIVE) && !jobByName.getCrontab().isEmpty()) {
                //定时任务
                try {
                    scheduleImportJob.executeScheduleImportJob(jobByName);
                } catch (SchedulerException e) {
                    return new OperationResult().setData(jobByName).setMessage("添加定时任务异常").setStateCode(StateCode.SYSTEM_ERROR.getValue());
                }
            }
            return new OperationResult(jobByName).setMessage("新增任务成功").setStateCode(StateCode.ADD_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("插入异常").setStateCode(StateCode.ADD_FAIL.getValue());
    }

    @Transactional(rollbackFor = Exception.class)
    @DeleteMapping("/mw/api/job/delete/{id}")
    public OperationResult deleteJob(@PathVariable Long id) {
        Job job = jobService.selectJobById(id);
        if (job != null && !job.getStatus().equals(GeneralStatus.DELETED)) {
            if (job.getStatus().equals(GeneralStatus.ACTIVE) && !job.getCrontab().isEmpty()) {
                try {
                    //删除定时任务
                    scheduleImportJob.removeScheduleImportJob(job.getName());
                } catch (SchedulerException e) {
                    log.error("移除定时任务失败！");
                    return new OperationResult().setMessage("删除定时任务失败！").setStateCode(StateCode.DELETE_FAIL.getValue());
                }
            }
            job.setStatus(GeneralStatus.DELETED.getIntLabel());
            jobService.updateJobSelective(job);
            log.info("成功删除:" + job);
            return new OperationResult().setData(job.getName()).setMessage("成功删除任务！").setStateCode(StateCode.DELETE_SUCCESS.getValue());
        }
        return new OperationResult().setMessage("删除失败,已删除或不存在！").setStateCode(StateCode.DELETE_FAIL.getValue());
    }

    @GetMapping("/mw/api/job/query/{id}")
    public OperationResult findJobById(@PathVariable Long id) {
        Job job = jobService.selectJobById(id);
        if (job != null && !job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            return new OperationResult().setData(job).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    @GetMapping("/mw/api/job/query/all")
    public OperationResult findAllJobs() {
        return new OperationResult().setData(jobService.findJobByStatus(GeneralStatus.ACTIVE.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }


    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/job/update/{id}")
    public OperationResult updateJob(@PathVariable Long id, @RequestBody Job job) {
        Job jobById = jobService.selectJobById(id);
        if (jobById != null && !jobById.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            jobById.setName(job.getName());
            jobById.setCrontab(job.getCrontab());
            jobById.setRemarks(job.getRemarks());
            jobById.setModifiedBy(job.getModifiedBy());
            jobById.setModifiedOn(new Date());
            jobById.setStatus(job.getStatus());
            jobService.updateJobSelective(jobById);
            if (jobById.getStatus().equals(GeneralStatus.ACTIVE) && jobById.getCrontab() != null) {
                scheduleImportJob.modifyJobTime(jobById.getName(), jobById.getCrontab());
            }
            return new OperationResult(jobById).setMessage("更新成功！").setStateCode(StateCode.EDIT_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("更新失败！").setStateCode(StateCode.EDIT_FAIL.getValue());
    }

    /**
     * 手动执行方法
     *
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @GetMapping("/mw/api/job/execute/{id}")
    public OperationResult ManualTrigger(@PathVariable Long id) throws Exception {
        Action action = null;
        Job job = jobService.selectJobById(id);
        if (job.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
            log.info("执行任务job");
            ArrayList<ActionHandler> actionHandlers = new ArrayList<>();
            for (int i = 0; i < job.getActionList().size(); i++) {
                action = job.getActionList().get(i);
                JSONObject prefix = action.getAddition().getJSONObject("addition").getJSONObject("dataSource").getJSONObject("addition");
                if (action.getType().equals(remoteAccess.getType()) && prefix.get("address") != null) {
                    ActionHandlerAdapter bean = applicationContext.getBean(remoteAccess.getBeanName(), ActionHandlerAdapter.class);
                    actionHandlers.add(bean);
                    bean.setAction(action);
                } else {
                    new OperationResult().setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage("执行异常！");
                }
            }

            SingleLinerJobActionHandlerChain singleLinerJobActionHandlerChain = new SingleLinerJobActionHandlerChain(actionHandlers);
            singleLinerJobActionHandlerChain.dataHandler(null);

            return new OperationResult(job);
        }
        log.error("状态未开启！");
        return new OperationResult(null).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage("状态未开启！");
    }

    //改变定时任务的状态
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/job/change/status/{id}")
    public OperationResult changeJobStatus(@PathVariable Long id) {
        Job job = jobService.selectJobById(id);
        Boolean flag = false;
        if (!job.getStatus().equals(GeneralStatus.DELETED)) {
            try {
                if (job.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
                    job.setStatus(GeneralStatus.DISABLE.getIntLabel());
                    jobService.updateJobSelective(job);
                    scheduleImportJob.removeScheduleImportJob(job.getName());
                    flag = true;
                    System.out.println("关闭定时任务！");
                } else {
                    job.setStatus(GeneralStatus.ACTIVE.getIntLabel());
                    jobService.updateJobSelective(job);
                    flag = scheduleImportJob.executeScheduleImportJob(job);
                    System.out.println("开启定时任务！ flag = "+ flag);
                }
            } catch (SchedulerException e) {
                e.printStackTrace();
                return new OperationResult(flag).setMessage("改变状态异常！").setStateCode(StateCode.SYSTEM_ERROR.getValue());
            }
            return new OperationResult(flag).setStateCode(StateCode.EDIT_SUCCESS.getValue()).setMessage("操作成功");
        }
        return new OperationResult(flag).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage("操作失败！");
    }

}
