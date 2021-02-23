package com.sailheader.middleware.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.action.consume.ConsumeData;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.chain.SingleLinerJobActionHandlerChain;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.common.helper.SearchUtil;
import com.sailheader.middleware.constant.ActionConsumeDataType;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.ActionType;
import com.sailheader.middleware.mapper.JobMapper;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.service.IJobService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.JOB_ACTION_CHAIN_EXECUTE_INTERRUPT;

@Service
public class JobServiceImpl extends ServiceImpl<JobMapper, Job> implements IJobService {
    @Autowired
    private JobMapper jobMapper;
    @Autowired
    private ApplicationContext applicationContext;

    public Job selectJobById(Long id) {
        return jobMapper.selectJobById(id);
    }

    public int updateJobSelective(Job job) {
        return jobMapper.updateById(job);
    }

    public int updateJob(Job job) {
        return jobMapper.updateJob(job);
    }

    public int insertSelective(Job job) {
        return jobMapper.insert(job);
    }

    public Job selectJobByName(String name) {
        QueryWrapper<Job> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("name",name);
        return jobMapper.selectOne(queryWrapper);
    }


    @Override
    public IPage<Job> selectPage(Page<Job> page,String conditionWithJson) throws Exception {
        QueryWrapper<Job> queryWrapper = SearchUtil.parseWhereSql(conditionWithJson);
        queryWrapper.ne("status",-1).orderByAsc("id");
        return jobMapper.selectJobByPage(page,queryWrapper);
    }

    public int checkForPresence(String name) {
        return jobMapper.checkForPresence(name);
    }

    // 检查任务执行序列
    public boolean checkJobIndex(Job job) {
        boolean flag = true;
        if (job == null) {
            return false;
        }
        if (job.getActionList().size() < 1) {
            return false;
        }
        int lastIndex = 1;
        for (int i = 0; i < job.getActionList().size(); i++) {
            if (lastIndex != job.getActionList().get(i).getIndex()) {
                flag = false;
                break;
            }
            lastIndex++;
        }
        return flag;
    }

    // 查询存活的定时任务
    public List<Job> findActiveTimedTask() {
        QueryWrapper<Job> queryWrapper = new QueryWrapper<>();
        List<Job> list = new ArrayList<>();
        queryWrapper.eq("status",1).orderByAsc("id");
        List<Job> jobList = jobMapper.selectList(queryWrapper);
        jobList.forEach((job) -> {
            if (job.getCrontab() != null && !job.getCrontab().trim().equals("")) {
                list.add(job);
            }
        });
        return list;
    }

    // 执行任务
    public ActionTempData executeJob(Job job, String tempData) throws Exception {
        ArrayList<ActionHandlerAdapter<?, ?>> actionHandlers = new ArrayList<>();
        ActionTempData<?> actionTempData = null;
        ActionHandlerAdapter<?, ?> bean;
        Action action;
        JSON json;
        try {
            json = JSON.parseObject(tempData);
        } catch (Exception e) {
            String msg = "输入的数据转换异常！ data : " + tempData;
            throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_SUITABLE_TYPE_NOT_FOUND,msg,e);
        }

        //  1、查询指定任务下的动作
        for (int i = 0; i < job.getActionList().size(); i++) {
            action = job.getActionList().get(i);
            if (action.getType() == null || ActionType.actionTypeById.get(action.getType()) == null) {
                throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_SUITABLE_TYPE_NOT_FOUND);
            }

            String beanName = ActionType.actionTypeById.get(action.getType()).getBeanName();
            try {
                bean = applicationContext.getBean(beanName, ActionHandlerAdapter.class);
            } catch (BeansException exception) {
                throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_IMPL_CLASS_NOT_FOUND, exception.getMessage());
            }
            actionTempData = new ActionTempData<JSON>(ActionStatus.INITIAL, json);


            // 2. 为bean找到一个消费输入数据的方式
            ActionConsumeDataType consumeDataType;
            if (ActionConsumeDataType.actionConsumeDataTypeById.get(action.getConsumeDataType()) != null) {
                consumeDataType = ActionConsumeDataType.actionConsumeDataTypeById.get(action.getConsumeDataType());
            } else {
                // 默认直接注入
                consumeDataType = ActionConsumeDataType.DIRECT;
            }

            String consumeDataBeanName = consumeDataType.getBeanName();
            ConsumeData consumeDataBean;
            try {
                consumeDataBean = applicationContext.getBean(consumeDataBeanName, ConsumeData.class);
            } catch (BeansException exception) {
                String msg = "未能成功找到相关消费动作的实现！consumeDataBeanName:" + consumeDataBeanName;
                throw MiddlewareException
                        .getInstance(ACTION_HANDLER_ADAPTER_ACTION_CONSUME_IMPL_NOT_FOUND, msg, exception);
            }

            bean.setConsumeData(consumeDataBean);

            //  3、将动作添加到链中
            actionHandlers.add(bean);
            bean.setAction(action);

        }
        //  4、执行单向链
        ActionTempData<?> dataHandler;
        try {
            SingleLinerJobActionHandlerChain singleLinerJobActionHandlerChain = new SingleLinerJobActionHandlerChain(
                    actionHandlers);
            dataHandler = singleLinerJobActionHandlerChain.dataHandler(actionTempData);
        } catch (MiddlewareException m) {
            throw MiddlewareException.getInstance(JOB_ACTION_CHAIN_EXECUTE_INTERRUPT, m);
        } catch (Exception e) {
            throw new Exception(e);
        }
        return dataHandler;
    }
}
