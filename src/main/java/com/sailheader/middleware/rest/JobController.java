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

import static com.sailheader.middleware.constant.ActionType.remoteAccess;



@Slf4j
@RestController(value = "JobController")
public class JobController {

    @Autowired
    private JobService jobService;
    @Autowired
    private ScheduleImportJob scheduleImportJob;
    @Autowired
    private ApplicationContext applicationContext;

    @PostMapping("/mw/api/job/create")
    public OperationResult createJob(@RequestBody Job job) {
        OperationResult operationResult = new OperationResult();
        int i = 0;
        Job jobByName = null;
        String msg = "添加任务异常";
        if (job.getName() == null) {
            log.error(msg + "，任务名不能为空！");
            return operationResult.setData(null).setMessage(msg + "，任务名不能为空！").setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (jobService.checkForPresence(job.getName()) > 0) {
            log.error(msg + "，任务名已存在！");
            return operationResult.setData(null).setMessage(msg + "，任务名已存在！").setStateCode(StateCode.ADD_FAIL.getValue());
        }
        job.setCreatedOn(new Date());
        i = jobService.insertSelective(job);
        jobByName = jobService.selectJobByName(job.getName());
        if (i < 1) {
            log.error(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }

        msg = "添加" + jobByName.getName() + "任务成功！";
        if (jobByName.getStatus().equals(GeneralStatus.ACTIVE) && !jobByName.getCrontab().isEmpty()) {
            //定时任务
            try {
                scheduleImportJob.executeScheduleImportJob(jobByName);
            } catch (SchedulerException e) {
                return new OperationResult().setData(jobByName).setMessage("添加定时任务异常").setStateCode(StateCode.SYSTEM_ERROR.getValue());
            }
            msg = "添加" + jobByName.getName() + "定时任务成功！";
        }
        log.info(msg);
        return new OperationResult(jobByName).setMessage(msg).setStateCode(StateCode.ADD_SUCCESS.getValue());
    }

    /**
     * 删除一个任务
     *
     * @param id 需要删除的任务id
     * @return 删除之后的结果集
     */
    @DeleteMapping("/mw/api/job/delete/{id}")
    public OperationResult deleteJob(@PathVariable Long id) {
        Job job = jobService.selectJobById(id);
        if (job == null) {
            log.error("该任务不存在!");
            return new OperationResult().setMessage("该任务不存在！").setStateCode(StateCode.DELETE_FAIL.getValue());
        }
        if (job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            log.error("该任务已删除!");
            return new OperationResult().setMessage("该任务已删除！").setStateCode(StateCode.DELETE_FAIL.getValue());
        }

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
        job.setName(job.getName() + "-" + job.getId());
        jobService.updateJobSelective(job);
        log.info("成功删除");
        return new OperationResult().setData(null).setMessage("成功删除任务！").setStateCode(StateCode.DELETE_SUCCESS.getValue());
    }

    /**
     * 查询指定任务
     *
     * @param id 待查询的任务id
     * @return 查询之后的结果集
     */
    @GetMapping("/mw/api/job/query/{id}")
    public OperationResult findJobById(@PathVariable Long id) {
        Job job = jobService.selectJobById(id);
        if (job != null && !job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            log.info("查询成功!");
            return new OperationResult().setData(job).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
        }
        log.error("不存在或已删除！");
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.QUERY_FAIL.getValue());
    }

    /**
     * 查询所有任务
     *
     * @return 查询之后的结果集
     */
    @GetMapping("/mw/api/job/query/all")
    public OperationResult findAllJobs() {
        return new OperationResult().setData(jobService.findJobByStatus(GeneralStatus.DELETED.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }


    /**
     * 更新任务内容
     *
     * @param id  待更新的任务id
     * @param job 更新内容对象
     * @return 更新之后的结果集
     */
    @PutMapping("/mw/api/job/update/{id}")
    public OperationResult updateJob(@PathVariable Long id, @RequestBody Job job) {
        OperationResult operationResult = new OperationResult();
        String msg = "更新失败！";
        Job jobById = jobService.selectJobById(id);
        if (jobById == null) {
            msg = "没有找到要更新的任务记录！";
            log.error(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (jobService.checkForPresence(job.getName()) > 0) {
            msg = "任务名已存在！";
            log.error(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (jobById.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            msg = "该任务已删除！";
            log.error(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        //关闭定时任务
        if (jobById.getCrontab() != null) {
            try {
                scheduleImportJob.removeScheduleImportJob(jobById.getName());
            } catch (SchedulerException e) {
                msg = "关闭定时任务异常";
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_SUCCESS.getValue());
            }
        }
        jobById.setName(job.getName()).setCrontab(job.getCrontab()).setRemarks(job.getRemarks())
                .setModifiedBy(job.getModifiedBy()).setModifiedOn(new Date());
        int i = jobService.updateJob(jobById);
        if (i < 1) {
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        Job jobByName = jobService.selectJobByName(jobById.getName());

        //重新启动定时任务触发时间
        if (job.getCrontab() != null && jobById.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
            try {
                scheduleImportJob.executeScheduleImportJob(jobByName);
            } catch (SchedulerException e) {
                msg = "更新定时任务异常";
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_SUCCESS.getValue());
            }
        }
        msg = "已更新";
        return operationResult.setData(jobById).setMessage(msg).setStateCode(StateCode.EDIT_SUCCESS.getValue());
    }

    /**
     * 手动执行方法
     *
     * @param id 执行的任务id
     * @return 执行之后的结果集
     */
    @GetMapping("/mw/api/job/execute/{id}")
    public OperationResult ManualTrigger(@PathVariable Long id) {
        OperationResult operationResult = new OperationResult();
        Action action = null;
        Job job = jobService.selectJobById(id);
        String msg = "执行失败！";
        if (job == null) {
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        if (!job.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
            msg = "状态未开启";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }

        //  1、查询指定任务下的动作
        ArrayList<ActionHandler> actionHandlers = new ArrayList<>();
        for (int i = 0; i < job.getActionList().size(); i++) {
            action = job.getActionList().get(i);
            JSONObject addition = action.getAddition();
            System.out.println("addition :" + addition);
            String address = (String) addition.getJSONObject("addition").getJSONObject("dataSource").getJSONObject("addition").get("address");
            if (action.getType().equals(remoteAccess.getType())
                    && addition.getShort("type").equals((short) 1)
                    && address != null) {
                ActionHandlerAdapter<?, ?> bean = applicationContext.getBean(remoteAccess.getBeanName(), ActionHandlerAdapter.class);
                //  2、将动作添加到链中
                actionHandlers.add(bean);
                bean.setAction(action);
            } else {
                return operationResult.setData(action).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
            }
        }
        //  3、执行单向链
        try {
            SingleLinerJobActionHandlerChain singleLinerJobActionHandlerChain = new SingleLinerJobActionHandlerChain(actionHandlers);
            singleLinerJobActionHandlerChain.dataHandler(null);
        } catch (Exception e) {
            msg = "执行失败！";
            return operationResult.setData(action).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        msg = "执行成功";
        return operationResult.setData(job).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 更新任务的状态
     *
     * @param id 待更新的定时任务的id
     * @return 更新状态之后的结果集
     */
    @PutMapping("/mw/api/job/change/status/{id}")
    public OperationResult changeJobStatus(@PathVariable Long id) {
        OperationResult operationResult = new OperationResult();
        Job job = jobService.selectJobById(id);
        Boolean flag = false;
        String msg = "操作失败!";
        if (job == null) {
            log.error(msg);
            return operationResult.setData(flag).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage(msg + ",该任务不存在！");
        }
        if (job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            log.error(msg);
            return operationResult.setData(flag).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage(msg + ",该任务或已删除！");
        }
        try {
            if (job.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
                msg = "关闭";
                job.setStatus(GeneralStatus.DISABLE.getIntLabel());
                jobService.updateJobSelective(job);
                scheduleImportJob.removeScheduleImportJob(job.getName());
            } else {
                msg = "开启";
                job.setStatus(GeneralStatus.ACTIVE.getIntLabel());
                jobService.updateJobSelective(job);
                scheduleImportJob.executeScheduleImportJob(job);
            }
        } catch (SchedulerException e) {
            msg = msg + "任务异常！";
            log.error(msg);
            return operationResult.setData(flag).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage("修改任务状态异常！");
        }
        msg = msg + "任务成功！";
        log.info(msg);
        flag = true;
        return operationResult.setData(flag).setStateCode(StateCode.EDIT_SUCCESS.getValue()).setMessage("修改任务状态成功");
    }

    @RequestMapping("/test")
    public void test() {
        System.out.println("SUCCESS" + new Date());
    }

}
