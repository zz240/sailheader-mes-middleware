package com.sailheader.middleware.rest;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.helper.ScheduleImportJob;
import com.sailheader.middleware.common.helper.TaskUtils;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.service.IJobService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.text.ParseException;
import java.util.Date;


@Slf4j
@RestController(value = "JobController")
public class JobController {
    @Autowired
    private IJobService jobService;
    @Autowired
    private ScheduleImportJob scheduleImportJob;

    @PostMapping("/mw/api/job/create")
    public OperationResult createJob(@RequestBody Job job) {
        OperationResult operationResult = new OperationResult();
        int i = 0;
        Job jobByName = null;
        String msg = "添加任务异常";
        if (job.getName() == null) {
            log.info(msg + "，任务名不能为空！");
            return operationResult.setData(null).setMessage(msg + ":任务名不能为空！").setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (jobService.checkForPresence(job.getName()) > 0) {
            log.info(msg + "，任务名已存在！");
            return operationResult.setData(null).setMessage(msg + ":任务名已存在！").setStateCode(StateCode.ADD_FAIL.getValue());
        }

        // 添加普通任务
        if (job.getCrontab() == null || job.getCrontab().equals("")) {
            job.setCreatedOn(new Date());
            i = jobService.insertSelective(job);
            jobByName = jobService.selectJobByName(job.getName());
        }
        if (i > 0) {
            msg = "添加" + jobByName.getName() + "任务成功！";
            log.info(msg);
            return operationResult.setData(jobByName).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
        }

        Boolean canDo;
        try {
            canDo = TaskUtils.isCanDoExpression(job.getCrontab());
        } catch (ParseException e) {
            msg = "定时表达式异常：" + e.getMessage();
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }

        if (canDo == false) {
            msg = "定时任务表达式格式有误";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        // 添加定时任务
        try {
            job.setCreatedOn(new Date());
            i = jobService.insertSelective(job);
            jobByName = jobService.selectJobByName(job.getName());
            scheduleImportJob.executeScheduleImportJob(jobByName);
        } catch (SchedulerException e) {
            log.info(jobByName + " -- 添加定时任务异常:" + e.getMessage());
            return operationResult.setData(null).setMessage("添加定时任务异常").setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (i < 1) {
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        msg = "添加" + jobByName.getName() + "定时任务成功！";
        log.info(msg);
        return operationResult.setData(jobByName).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
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
            log.info("该任务不存在!");
            return new OperationResult().setMessage("该任务不存在！").setStateCode(StateCode.DELETE_FAIL.getValue());
        }
        if (job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            log.info("该任务已删除!");
            return new OperationResult().setMessage("该任务已删除！").setStateCode(StateCode.DELETE_FAIL.getValue());
        }

        if (job.getStatus().equals(GeneralStatus.ACTIVE) && !job.getCrontab().isEmpty()) {
            try {
                //删除定时任务
                scheduleImportJob.removeScheduleImportJob(job.getName());
            } catch (SchedulerException e) {
                log.error("移除定时任务失败！"+e.getMessage());
                return new OperationResult().setMessage("删除定时任务失败！"+e.getMessage()).setStateCode(StateCode.DELETE_FAIL.getValue());
            }
        }
        job.setStatus(GeneralStatus.DELETED.getIntLabel());
        job.setName(job.getName() + "-" + job.getId());
        jobService.updateJobSelective(job);
        log.info("成功删除");
        return new OperationResult().setData(null).setMessage("成功删除任务！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
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
            return new OperationResult().setData(job).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
        }
        log.info("查询id为：" + id + " 任务不存在或已删除！:" + job);
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.QUERY_FAIL.getValue());
    }

    /**
     * 查询所有任务
     *
     * @return 查询之后的结果集
     */
    @PostMapping("/mw/api/job/query/all")
    public OperationResult<IPage<Job>> findAllJobs(@RequestParam(name = "pageNum",required = false,defaultValue = "1") Integer pageNum,
                                       @RequestParam(name = "pageSize",required = false,defaultValue = "10") Integer pageSize,
                                       @RequestBody(required = false) String condition) {
        OperationResult result = new OperationResult();
        IPage<Job> jobIPage = null;
        try {
            jobIPage = jobService.selectPage(new Page(pageNum,pageSize),condition);
        } catch (Exception e) {
            log.info(e.getMessage());
            return result.setMessage("条件查询异常,请检查 查询条件 后重试！").setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        return result.setData(jobIPage)
                .setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
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
        boolean canDo = false;
        Job jobById = jobService.selectJobById(id);
        if (jobById == null) {
            msg = "没有找到要更新的任务记录！";
            log.info(msg + "id = " + id);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (job.getCrontab() != null || job.getCrontab() != "") {
            try {
                canDo = TaskUtils.isCanDoExpression(job.getCrontab());
            } catch (ParseException e) {
                msg = "定时表达式有误" + e.getMessage();
                return operationResult.setData(job.getCrontab()).setStateCode(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
            }
        }
        if (jobService.checkForPresence(job.getName()) > 0) {
            if (!job.getName().equals(jobById.getName())) {
                msg = "任务名已存在";
                log.info(jobById + "修改失败：" + msg);
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
            }
        }
        if (jobById.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            msg = "该任务已删除！";
            log.info("id =" + id + " 的任务已删除！");
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        //关闭定时任务
        if (jobById.getCrontab() != null || jobById.getCrontab() != "") {
            try {
                scheduleImportJob.removeScheduleImportJob(jobById.getName());
            } catch (SchedulerException e) {
                msg = "关闭定时任务异常 - "+e.getMessage();
                log.info(msg);
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_FAIL.getValue());
            }
        }
        jobById.setName(job.getName()).setCrontab(job.getCrontab()).setRemarks(job.getRemarks())
                .setModifiedBy(job.getModifiedBy()).setModifiedOn(new Date());
        int i = jobService.updateJob(jobById);
        if (i < 1) {
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_FAIL.getValue());
        }
        Job jobByName = jobService.selectJobByName(jobById.getName());

        //重新启动定时任务触发时间
        if (canDo && jobById.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
            try {
                scheduleImportJob.executeScheduleImportJob(jobByName);
            } catch (SchedulerException e) {
                msg = "更新定时任务异常";
                log.info(msg + " : " + e.getMessage());
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
            }
        }
        msg = "已更新";
        log.info("更新id为：" + id + "的任务成功！");
        return operationResult.setData(jobById).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 手动执行方法
     *
     * @param id 执行的任务id
     * @return 执行之后的结果集
     */
    @PostMapping("/mw/api/job/execute/{id}")
    public OperationResult ManualTrigger(@PathVariable Long id, @RequestBody(required = false) String tempData) {
        OperationResult operationResult = new OperationResult();
        Job job = jobService.selectJobById(id);
        String msg;
        if (job == null) {
            msg = "执行任务为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        if (!job.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())) {
            msg = "状态未开启";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        if (!jobService.checkJobIndex(job)) {
            msg = "任务执行顺序有误，请检查后再试";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        ActionTempData actionTempData;
        try {
            actionTempData = jobService.executeJob(job, tempData);
        } catch (Exception e) {
            msg = "执行任务过程异常 - " + e.getMessage();
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        msg = "执行成功";
        log.info(msg);
        return operationResult.setData(actionTempData.getBody()).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
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
            log.info(msg);
            return operationResult.setData(flag).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage(msg + " 该任务不存在！");
        }
        if (job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            log.info(msg);
            return operationResult.setData(flag).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage(msg + " 该任务或已删除！");
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
            msg = msg + "任务修改异常！";
            log.info(msg);
            return operationResult.setData(flag).setStateCode(StateCode.SYSTEM_ERROR.getValue()).setMessage(msg);
        }
        msg = msg + "任务修改成功！";
        log.info(msg);
        flag = true;
        return operationResult.setData(flag).setStateCode(StateCode.SYSTEM_SUCCESS.getValue()).setMessage(msg);
    }

}
