package com.sailheader.middleware;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.rest.JobController;

import jdk.nashorn.internal.ir.annotations.Ignore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Ignore
public class JobTest {
    @Autowired
    private JobController jobController;


    //添加job - 完整流程
    @Test
    public void createJob(){
        Job job = new Job();
        job.setName("test011");
        job.setCreatedBy(2);
        job.setRemarks("ceshi1");
        job.setStatus((short) 1);

        OperationResult result = jobController.createJob(job);
        assertEquals(result.getStateCode(), StateCode.ADD_SUCCESS.getValue());
//        Exception exception = assertThrows(Exception.class, () -> );
    }

    // 添加job - 相同名称
    @Test
    public void createJob1(){
        Job job = new Job();
        job.setName("test01");
        OperationResult result = jobController.createJob(job);
        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
//        Exception exception = assertThrows(Exception.class, () -> );
    }

    // 删除job - 正确流程
    @Test
    public void deleteJob1(){
        Long id = Long.valueOf(26);
        OperationResult result = jobController.deleteJob(id);
        assertEquals(result.getStateCode(), StateCode.DELETE_SUCCESS.getValue());
    }

    //删除job - 范围外的id
    @Test
    public void deleteJob2(){
        Long id = Long.valueOf(100);
        OperationResult result = jobController.deleteJob(id);
        assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
    }

    //更改job状态 - 正确流程
    @Test
    public void changeJobStatus(){
        OperationResult result = jobController.changeJobStatus(Long.valueOf(3));
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.SYSTEM_SUCCESS.getValue());
    }

    //更改job状态 - 不是定时任务
    @Test
    public void changeJobStatus1(){
        OperationResult result = jobController.changeJobStatus(Long.valueOf(27));
        System.out.println(result);

        assertEquals(result.getStateCode(),StateCode.SYSTEM_FAIL.getValue());
    }

    //更改job状态 - 已删除的任务
    @Test
    public void changeJobStatus2(){
        OperationResult result = jobController.changeJobStatus(Long.valueOf(25));
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.SYSTEM_ERROR.getValue());
    }

    // 查询指定job - 已删除的任务
    @Test
    public void findJobTest(){
        OperationResult result = jobController.findJobById(Long.valueOf(25));
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.QUERY_FAIL.getValue());

    }

    // 查询指定job - 不存在的任务
    @Test
    public void findJobTest1(){
        OperationResult result = jobController.findJobById(Long.valueOf(100));
        System.out.println(result);
        //已删除
        assertEquals(result.getStateCode(),StateCode.QUERY_FAIL.getValue());
//        assertEquals(result.getStateCode(),StateCode.QUERY_SUCCESS.getValue());
    }

    // 查询指定job - 完整流程
    @Test
    public void findJobTest3(){
        OperationResult result = jobController.findJobById(Long.valueOf(27));
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.QUERY_SUCCESS.getValue());
    }

    @Test
    public void findAllJobTest1(){
        OperationResult result = jobController.findAllJobs();
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.SYSTEM_SUCCESS.getValue());
    }

    //更新job - 范围之外的id
    @Test
    public void updateJobTest01(){
        Job job = new Job();
        OperationResult result = jobController.updateJob((long) 100, job);
        assertEquals(result.getStateCode(),StateCode.EDIT_FAIL.getValue());
    }

    //更新job - 已删除的任务
    @Test
    public void updateJobTest02(){
        Job job = new Job();
        job.setName("updateJobTest02");
        OperationResult result = jobController.updateJob((long) 25, job);
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.EDIT_FAIL.getValue());
    }


    //同一任务，更新相同参数
    @Test
    public void updateJobTest03(){
        Job job = new Job();
        job.setName("rjl");
        job.setCrontab("0/3 * * * * ?");
        job.setRemarks("updateJobTest03");
        job.setModifiedBy(2);
        OperationResult result = jobController.updateJob((long) 2, job);
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.EDIT_FAIL.getValue());
    }

    //更新job - 完整流程
    @Test
    public void updateJobTest04(){
        Job job = new Job();
        job.setName("rjl");
        job.setCrontab("0/4 * * * * ?");
        job.setRemarks("人机料");
        job.setModifiedBy(2);
        OperationResult result = jobController.updateJob((long) 2, job);
        System.out.println(result);
        assertEquals(result.getStateCode(),StateCode.EDIT_SUCCESS.getValue());
    }


    //手动执行流程 - 完整流程
    @Test
    public void ManualTriggerTest01(long id){
        id = 3;
        try {
            OperationResult result = jobController.ManualTrigger(id,null);
            System.out.println(result);
            assertEquals(result.getStateCode(),StateCode.SYSTEM_SUCCESS.getValue());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



}
