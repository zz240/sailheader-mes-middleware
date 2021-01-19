package com.sailheader.middleware.rest;

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
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Date;

import static com.sailheader.middleware.constant.ActionType.remoteAccess;


@RestController
public class JobController {
    @Autowired
    private JobService jobService;
    @Autowired
    private ScheduleImportJob scheduleImportJob;
    @Autowired
    private ApplicationContext applicationContext;

    @PostMapping("/mw/api/job/create")
    public OperationResult createJob(@RequestBody Job job){
        int i = jobService.insertSelective(job);
        Job jobByName = jobService.selectJobByName(job.getName());
        if (i == 1){
            if (jobByName.getStatus().equals(GeneralStatus.ACTIVE) && !jobByName.getCrontab().isEmpty()){
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

    @DeleteMapping("/mw/api/job/delete/{id}")
    public OperationResult deleteJob(@PathVariable Long id){
        Job job = jobService.selectJobById(id);
        if (job != null && !job.getStatus().equals(GeneralStatus.DELETED)){
//            if ()
            job.setStatus(GeneralStatus.DELETED.getIntLabel());
            jobService.updateJobSelective(job);
            return new OperationResult(id).setMessage("删除成功").setStateCode(StateCode.DELETE_SUCCESS.getValue());
        }
        return new OperationResult().setMessage("删除失败,已删除或不存在！").setStateCode(StateCode.DELETE_FAIL.getValue());
    }

    @GetMapping("/mw/api/job/query/{id}")
    public OperationResult findJobById(@PathVariable Long id){
        Job job = jobService.selectJobById(id);
        if (job != null && !job.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            return new OperationResult().setData(job).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    @GetMapping("/mw/api/job/query/all")
    public OperationResult findAllJobs(){
        return new OperationResult().setData(jobService.findJobByStatus(GeneralStatus.ACTIVE.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }


    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/job/update/{id}")
    public OperationResult updateJob(@PathVariable Long id,@RequestBody Job job){
        Job jobById = jobService.selectJobById(id);
        if (jobById != null && jobById.getStatus().equals(GeneralStatus.ACTIVE.getIntLabel())){
            jobById.setName(job.getName());
            jobById.setCrontab(job.getCrontab());
            jobById.setRemarks(job.getRemarks());
            jobById.setModifiedBy(job.getModifiedBy());
            jobById.setModifiedOn(new Date());
            jobService.updateJobSelective(jobById);
            return new OperationResult(jobById).setMessage("更新成功！").setStateCode(StateCode.EDIT_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("更新失败！").setStateCode(StateCode.EDIT_FAIL.getValue());
    }

    /**
     * 手动执行方法
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @GetMapping("/mw/api/job/execute/{id}")
    public OperationResult ManualTrigger(@PathVariable Long id) throws Exception {
        Action action = null;
        Job job = jobService.selectJobById(id);
        ArrayList<ActionHandler> actionHandlers = new ArrayList<>();
        for (int i = 0; i < job.getActionList().size(); i++) {
            action = job.getActionList().get(i);
            if (action.getType().equals(remoteAccess.getType()) && action.getAddition().get("address")!= null){
                ActionHandlerAdapter<?, ?> bean = applicationContext.getBean(remoteAccess.getBeanName(), ActionHandlerAdapter.class);
                actionHandlers.add(bean);
                bean.setAction(action);
            }
        }
        SingleLinerJobActionHandlerChain singleLinerJobActionHandlerChain = new SingleLinerJobActionHandlerChain(actionHandlers);
        singleLinerJobActionHandlerChain.dataHandler(null);

        return new OperationResult(job);
    }

}
