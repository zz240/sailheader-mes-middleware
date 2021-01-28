package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.rest.ActionController;
import jdk.nashorn.internal.ir.annotations.Ignore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class ActionTest {
    @Autowired
    private ActionController actionController;

    @Nested
    @DisplayName("check create action")
    class checkCreateAction{
        JSONObject jsonObject;
        Action action;
        JSONObject dataSource;

        @BeforeEach
        public void initAction(){
            jsonObject = new JSONObject();
            action = new Action();
            dataSource = new JSONObject();
            int i = 1;

            dataSource.put("method","POST");

            jsonObject.put("type",1);
            jsonObject.put("name","addition_dataSource_name");
            jsonObject.put("address","http://localhost:3361/test");
            jsonObject.put("addition",dataSource);

            action.setName("checkActionTest"+i).setType((short)1).setJobId((long)20).setIndex(1);
            i ++;
        }

        // 添加action - 正确流程
        @Test
        @Order(1)
        public void createActionTest01(){
            action.setAddition(jsonObject);
            OperationResult result = actionController.createAction(action);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        // 添加动作 - name重复
        @Test
        @Order(2)
        public void createActionTest02(){
            action.setAddition(jsonObject);
            action.setName("checkActionTest1");
            OperationResult result = actionController.createAction(action);
            assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
        }

        // 添加动作 - type为空
        @Test
        @Order(3)
        public void createActionTest03(){
            action.setAddition(jsonObject);
            action.setType(null);
            OperationResult result = actionController.createAction(action);
            assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
        }

        // 添加动作 - 空对象
        @Test
        @Order(4)
        public void createActionTest04(){
            action = null;
            OperationResult result = actionController.createAction(action);
            assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
        }


    }

    @Nested
    @DisplayName("check delete action")
    class checkDeleteAction{
        /**
         * 先添加一个动作，再将动作的id设置在参数中
         */

        // 删除动作 - 正确流程
        @Test
        @Order(1)
        public void testDelActionNormal(){
            Long id = Long.valueOf(24);
            OperationResult result = actionController.deleteAction(id);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        // 删除动作 - 范围之外的动作id
        @Test
        @Order(2)
        public void testDelActionOutRange(){
            Long id = Long.valueOf(10000);
            OperationResult result = actionController.deleteAction(id);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
        }

        // 删除动作 - 删除动作的id
        @Test
        @Order(3)
        public void testDelActionDeleted(){
            Long id = Long.valueOf(24); //已删除
            OperationResult result = actionController.deleteAction(id);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
        }
    }

    @Nested
    @DisplayName("check update action")
   class checkUpdateAction{
        JSONObject jsonObject;
        Action action;
        JSONObject dataSource;

        @BeforeEach
        public void initAction(){
            jsonObject = new JSONObject();
            action = new Action();
            dataSource = new JSONObject();
            int i = 1;

            dataSource.put("method","POST");

            jsonObject.put("type",1);
            jsonObject.put("name","addition_dataSource_name");
            jsonObject.put("address","http://localhost:3361/test");
            jsonObject.put("addition",dataSource);

            action.setName("checkUpdateAction"+i).setType((short)1).setJobId((long)20).setIndex(1);
            i ++;
        }

        // 更新指定动作 - 正确流程
        @Test
        @Order(1)
        public void testUpdateActionNormal(){
            action.setAddition(jsonObject);
            System.out.println(action);
            OperationResult result = actionController.updateActionById((long) 24, action);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        // 更新指定动作 - name重复
        @Test
        @Order(2)
        public void testUpdateActionRepeatName(){
            action.setAddition(jsonObject);
            action.setName("demo03");
            OperationResult result = actionController.updateActionById((long) 24, action);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        // 更新指定动作 - action is null
        @Test
        @Order(3)
        public void testUpdateActionIsNull(){
            Action action = new Action();
            OperationResult result = actionController.updateActionById((long) 24, action);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        // 更新指定动作 - addition中的参数验证
        @Test
        @Order(4)
        public void testUpdateActionCheckAddition(){
            action.setAddition(null);
            OperationResult result = actionController.updateActionById((long) 24, action);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

   }



    @Nested
    @DisplayName("check select action")
   class checkFindAction{
        // 查询指定动作 - 正确流程
        @Test
        public void findActionTest01(){
            OperationResult result = actionController.findActionById((long) 24);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        // 查询指定动作 - 范围之外的动作id
        @Test
        public void findActionTest02(){
            OperationResult result = actionController.findActionById((long) 10000);
            assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
        }

        // 查询指定动作 - 已删除的id
        @Test
        public void findActionTest03(){
            OperationResult result = actionController.findActionById((long) 1);
            assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
        }
   }


    @Nested
    @DisplayName("test check Action Pass")
    class checkActionPass{
        JSONObject datasource;
        JSONObject addition;
        Action action;
        Map map;

        @BeforeEach
        public void initJsonObject() {
            map = new HashMap();
            datasource = new JSONObject();
            action = new Action();
            addition = new JSONObject();

            addition.put("method","POST");

            datasource.put("type",1);
            datasource.put("name","datasourceTest");
            datasource.put("address","http://localhost:3361/test");
            datasource.put("addition",addition);

            action.setType((short)1).setName("testAction").setJobId((long)33).setIndex(3)
                    .setAddition(datasource).setTempData(addition);

        }

        @Test
        public void testCheckPassNormal(){
            map.put("action",action);
            map.put("inputData",addition);
            OperationResult operationResult = actionController.checkActionPass(map);
            assertEquals(StateCode.SYSTEM_SUCCESS.getValue(),operationResult.getStateCode());
        }

        @Test
        public void testCheckPassNoJobId(){
            action.setJobId(null);
            map.put("action",action);
            map.put("inputData",addition);
            OperationResult operationResult = actionController.checkActionPass(map);
            assertEquals(StateCode.SYSTEM_FAIL.getValue(),operationResult.getStateCode());
        }

        @Test
        public void testCheckPassNoType(){
            action.setType(null);
            map.put("action",action);
            map.put("inputData",addition);
            OperationResult operationResult = actionController.checkActionPass(map);
            assertEquals(StateCode.SYSTEM_FAIL.getValue(),operationResult.getStateCode());
        }

        @Test
        public void testCheckPassNoIndex(){
            action.setIndex(0);
            map.put("action",action);
            map.put("inputData",addition);
            OperationResult operationResult = actionController.checkActionPass(map);
            assertEquals(StateCode.SYSTEM_FAIL.getValue(),operationResult.getStateCode());
        }

        @Test
        public void testCheckPassNoAddition(){
            action.setAddition(null);
            map.put("action",action);
            map.put("inputData",addition);
            OperationResult operationResult = actionController.checkActionPass(map);
            assertEquals(StateCode.SYSTEM_FAIL.getValue(),operationResult.getStateCode());
        }

        @Test
        public void testCheckPassNoTempData(){
            action.setAddition(null);
            map.put("action",action);
            map.put("inputData",addition);
            OperationResult operationResult = actionController.checkActionPass(map);
            assertEquals(StateCode.SYSTEM_FAIL.getValue(),operationResult.getStateCode());
        }

        @Test
        public void testCheckPassNoInputData(){
            map.put("action",action);
            OperationResult operationResult = actionController.checkActionPass(map);
            assertEquals(StateCode.SYSTEM_SUCCESS.getValue(),operationResult.getStateCode());
        }





    }


}
