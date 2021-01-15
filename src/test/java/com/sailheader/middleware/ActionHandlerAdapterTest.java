package com.sailheader.middleware;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.helper.MappingJsonData;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试单个动作数据注入并输出流程
 */
@SpringBootTest
public class ActionHandlerAdapterTest {

    // 字段映射测试
    ActionHandlerAdapter<JSON, JSON> actionHandlerAdapter = new ActionHandlerAdapter<JSON, JSON>() {
        @Override
        protected ActionTempData<JSON> execute(ActionTempData<JSON> object) throws Exception {
            return object;
        }

        @Override
        protected void validateActionData() throws Exception {

        }
    };

    /**
     * 测试 输入数据 对 action addition 的注入
     */
    @Test
    public void testJsonMappingForActionAddition() throws Exception {
        // 1. 构造Action
        Action action = new Action();
        JSON json = (JSON) JSON.parse("{'simpleName': \"#{#lastData['name']}\"}");
        action.setAddition((JSONObject) json);

        // 2. 构造输入数据
        JSON inputData = (JSON) JSON.parse("{'name': 'steven'}");
        ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, inputData);

        // 3. 通过动作查看 addition 转化后的内容
        actionHandlerAdapter.setAction(action);
        actionHandlerAdapter.dataHandler(actionTempData);

        assertEquals("steven", action.getAddition().getString("simpleName"));

    }

    /**
     * 测试输入数据和当前数据对 输出内容 的注入
     */
    @Test
    public void testJsonMappingForOut() throws Exception {
        // 1. 构造Action
        Action action = new Action();
        JSON json = (JSON) JSON.parse("{'simpleName': \"#{#lastData['name']}\"}");
        action.setTempData((JSONObject) json);

        // 2. 构造输入数据
        JSON inputData = (JSON) JSON.parse("{'name': 'steven'}");
        ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, inputData);

        // 3. 通过动作查看 addition 转化后的内容
        actionHandlerAdapter.setAction(action);
        ActionTempData<JSON> actionTempData1 = actionHandlerAdapter.dataHandler(actionTempData);

        assertEquals("steven", ((JSONObject)(actionTempData1).getBody()).getString("simpleName"));
    }
}
