package com.sailheader.middleware.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sailheader.middleware.model.Job;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 执行任务表 Mapper 接口
 * </p>
 *
 * @author Elizer
 * @since 2021-02-19
 */
public interface JobMapper extends BaseMapper<Job> {
    int checkForPresence(String name);

    int updateJob(Job job);

    Job selectJobById(Long id);

    IPage<Job> selectJobByPage(Page<Job> page,@Param(Constants.WRAPPER) Wrapper wrapper);
}
