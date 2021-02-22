package com.sailheader.middleware.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.model.Job;

import java.util.List;

/**
 * 执行任务表 服务类
 *
 * @author Elizer
 * @since 2021-02-19
 */
public interface IJobService extends IService<Job> {

    Job selectJobById(Long id);

    int updateJobSelective(Job job);

    int updateJob(Job job);

    int insertSelective(Job job);

    Job selectJobByName(String name);

    IPage<Job> selectPage(Page<Job> page,String conditionWithJson) throws Exception;

    int checkForPresence(String name);

    boolean checkJobIndex(Job job);

    List<Job> findActiveTimedTask();

    ActionTempData executeJob(Job job, String tempData)throws Exception;

}
