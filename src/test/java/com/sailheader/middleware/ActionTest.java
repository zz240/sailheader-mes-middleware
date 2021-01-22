package com.sailheader.middleware;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.rest.ActionController;
import jdk.nashorn.internal.ir.annotations.Ignore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Ignore
public class ActionTest {
    Action action = new Action();
    @Autowired
    private ActionController actionController;

    // 添加动作 - 空对象
    @Test
    public void createAction(){
        OperationResult result = actionController.createAction(action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
    }

    // 添加动作 - name重复
    @Test
    public void createActionTest02(){
        action.setName("test");
        action.setType((short)1);
        OperationResult result = actionController.createAction(action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
    }

    // 添加动作 - type为空
    @Test
    public void createActionTest03(){
        action.setName("testtest");
        OperationResult result = actionController.createAction(action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
    }

    // 添加action - 正确流程
    @Test
    public void createActionTest04(){
        action.setName("测试正确流程01");
        action.setType((short)2);
        action.setJobId((long)5);
        action.setIndex(1);
        OperationResult result = actionController.createAction(action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.ADD_SUCCESS.getValue());
    }

    // 删除动作 - 正确流程
    @Test
    public void deleteActionTest01(){
        Long id = Long.valueOf(4);
        OperationResult result = actionController.deleteAction(id);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.DELETE_SUCCESS.getValue());
    }

    // 删除动作 - 范围之外的动作id
    @Test
    public void deleteActionTest02(){
        Long id = Long.valueOf(10000);
        OperationResult result = actionController.deleteAction(id);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
    }

    // 删除动作 - 删除动作的id
    @Test
    public void deleteActionTest03(){
        Long id = Long.valueOf(4); //已删除
        OperationResult result = actionController.deleteAction(id);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
    }

    // 更新指定动作 - 正确流程
    @Test
    public void updateActionTest01(){
        action.setName("updateTest01");
        action.setType((short) 1);
        action.setJobId((long) 66);
        action.setIndex(3);
        JSONObject json = new JSONObject();
        json.put("datasource","{'name':'updateActionTest01'}");
        action.setAddition(json);
        action.setModifiedBy(3);
        OperationResult result = actionController.updateActionById((long) 6, action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_SUCCESS.getValue());
    }

    // 更新指定动作 - name重复
    @Test
    public void updateActionTest02(){
        action.setName("updateTest01");
        OperationResult result = actionController.updateActionById((long) 6, action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    // 更新指定动作 - 空对象，空name
    @Test
    public void updateActionTest03(){
        OperationResult result = actionController.updateActionById((long) 6, action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    // 更新指定动作 - addition中的参数验证
    @Test
    public void updateActionTest04_validationParameters(){
        action.setName("updateTest04");
        String json = "{\"dataSource\":{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}}";
        action.setAddition(JSONObject.parseObject(json));
        OperationResult result = actionController.updateActionById((long) 15, action);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_SUCCESS.getValue());
    }

    // 查询指定动作 - 正确流程
    @Test
    public void findActionTest01(){
        OperationResult result = actionController.findActionById((long) 6);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.QUERY_SUCCESS.getValue());
    }

    // 查询指定动作 - 范围之外的动作id
    @Test
    public void findActionTest02(){
        OperationResult result = actionController.findActionById((long) 100);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
    }

    // 查询指定动作 - 已删除的id
    @Test
    public void findActionTest03(){
        OperationResult result = actionController.findActionById((long) 4);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
    }

}
