package com.sailheader.middleware.service;

import com.sailheader.middleware.mapper.JobDAO;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.model.JobExample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobService {
    @Autowired
    private JobDAO jobDAO;

    public Job selectJobById(Long id){
        return jobDAO.selectByPrimaryKey(id);
    }

    public int deleteJobById(Long id){
        return jobDAO.deleteJobById(id);
    }

    public int updateJobSelective(Job job){
        return jobDAO.updateByPrimaryKeySelective(job);
    }

    public int updateJob(Job job){
        return jobDAO.updateByPrimaryKey(job);
    }

    public int insertSelective(Job job){
        return jobDAO.insertSelective(job);
    }

    public Job selectJobByName(String name){
        return jobDAO.selectJobByName(name);
    }

    public List<Job> findJobByStatus(Short status){
        JobExample example = new JobExample();
        example.createCriteria().andStatusNotEqualTo(status);
        return jobDAO.selectByExample(example);
    }

    public int checkForPresence(String name){
        return jobDAO.checkForPresence(name);
    }

}
