package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Job;

import jdk.nashorn.internal.ir.annotations.Ignore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class JobTest {
    @Autowired
    private JobController jobController;

    @Nested
    @DisplayName("check add Job")
    class checkAddJobTests {
        //添加job - 完整流程
        @Test
        @Order(1)
        public void createJob() {
            Job job = new Job();
            job.setName("单元测试用例");
            job.setCreatedBy(1);
            job.setRemarks("单元测试新增的任务1");
            job.setStatus((short) 1);

            OperationResult result = jobController.createJob(job);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        // 添加job - 相同名称
        @Test
        @Order(2)
        public void createJob2() {
            Job job = new Job();
            job.setName("单元测试用例");
            job.setCreatedBy(1);
            job.setRemarks("单元测试新增的任务1");
            job.setStatus((short) 1);
            OperationResult result = jobController.createJob(job);
            assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
        }

        // 添加job - 任务名为空
        @Test
        @Order(3)
        public void createJob3() {
            Job job = new Job();
            job.setName(null);
            OperationResult result = jobController.createJob(job);
            assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
        }
    }


    @DisplayName("check delete job tests")
    @Nested
    class checkDelJobTests {
        // 删除job - 正确流程
        @Test
        @Order(1)
        public void deleteJob1() {
            Long id = Long.valueOf(56);
            OperationResult result = jobController.deleteJob(id);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        //删除job - 范围外的id
        @Test
        @Order(2)
        public void deleteJob2() {
            Long id = Long.valueOf(10000);
            OperationResult result = jobController.deleteJob(id);
            assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
        }

        //删除job - 范围外的id
        @Test
        @Order(3)
        public void deleteJob3() {
            Long id = Long.valueOf(56);
            OperationResult result = jobController.deleteJob(id);
            assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
        }
    }

    @Nested
    @DisplayName("check update job tests")
    class checkUpdateJobTests{

        //更改job状态 - 正确流程
        @Test
        public void changeJobStatus() {
            OperationResult result = jobController.changeJobStatus(Long.valueOf(56));
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        //更改job状态 - 已删除的任务
        @Test
        public void changeJobStatus2() {
            OperationResult result = jobController.changeJobStatus(Long.valueOf(30));
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_ERROR.getValue());
        }

        //更新job - 范围之外的id
        @Test
        @Order(1)
        public void updateJobTest01() {
            Job job = new Job();
            OperationResult result = jobController.updateJob((long) 100, job);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        //更新job - 已删除的任务
        @Test
        @Order(2)
        public void updateJobTest02() {
            Job job = new Job();
            job.setName("updateJobTest02");
            OperationResult result = jobController.updateJob((long) 30, job);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }


        //同一任务，更新相同参数
        @Test
        @Order(3)
        public void updateJobTest03() {
            Job job = new Job();
            job.setName("rjl");
            job.setCrontab("0/20 * * * * ?");
            job.setRemarks("updateJobTest03");
            job.setModifiedBy(2);
            OperationResult result = jobController.updateJob((long) 2, job);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        //重复的名字
        @Test
        @Order(4)
        public void updateJobTest04() {
            Job job = new Job();
            job.setName("单元测试用例");
            job.setCrontab("0/20 * * * * ?");
            job.setRemarks("rjl");
            job.setModifiedBy(2);
            OperationResult result = jobController.updateJob((long) 2, job);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        //更新job - 不同参数
        @Test
        @Order(5)
        public void updateJobTest05() {
            Job job = new Job();
            job.setName("rjl");
            job.setCrontab(null);
            job.setRemarks("人机料");
            job.setModifiedBy(2);
            OperationResult result = jobController.updateJob((long) 2, job);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }
    }

    @Nested
    @DisplayName("check find Job Tests")
    class checkFindJobTests{
        // 查询指定job - 已删除的任务
        @Test
        public void findJobTest() {
            OperationResult result = jobController.findJobById(Long.valueOf(30));
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());

        }

        // 查询指定job - 不存在的任务
        @Test
        public void findJobTest1() {
            OperationResult result = jobController.findJobById(Long.valueOf(100));
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
        }

        // 查询指定job
        @Test
        public void findJobTest3() {
            OperationResult result = jobController.findJobById(Long.valueOf(3));
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

    }


    @Nested
    @DisplayName("check Manual Trigger Job")
    class checkManualTriggerJobTests {

        /**
         * 测试 手动执行任务 - 正常流程
         */
        @Test
        @Order(1)
        public void testMethodNormal() {
            long id = 56L;
            OperationResult result = jobController.ManualTrigger(id, null);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        /**
         * 测试 手动执行任务 - job 未开启
         */
        @Test
        @Order(2)
        public void testJobStatusIsClose() {
            long id = 27L;
            OperationResult result = jobController.ManualTrigger(id, null);
            assertEquals(result.getMessage(), "状态未开启");
        }

        /**
         * 测试 手动执行任务 - job 设置 执行顺序异常
         */
        @Test
        public void testActionByJobIdIndex() {
            long id = 31L;
            OperationResult result = jobController.ManualTrigger(id, null);
            assertEquals(result.getMessage(), "任务执行顺序有误，请检查后再试");
        }

    }

}
