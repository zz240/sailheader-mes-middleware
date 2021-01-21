package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.model.JobExample;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobDAO {

    Job selectByPrimaryKey(Long id);

    int deleteJobById(Long id);

    int updateByPrimaryKeySelective(Job job);

    int insertSelective(Job job);

    Job selectJobByName(String name);

    int updateByPrimaryKey(Job job);

    List<Job> selectByExample(JobExample job);

    int checkForPresence(String name);


}
