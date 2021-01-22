package com.sailheader.middleware.action.consume;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.constant.ActionConsumeDataType;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.ActionType;
import com.sailheader.middleware.model.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试不消费数据直接传入
 */
@SpringBootTest
class DirectConsumeTest {
    @Autowired
    ApplicationContext context;

    // 抽象类空构造
    ActionHandlerAdapter<JSON, JSON> actionHandlerAdapter = new ActionHandlerAdapter<JSON, JSON>() {
        @Override
        protected ActionTempData<JSON> execute(ActionTempData<JSON> object) throws Exception {
            return object;
        }

        @Override
        protected void validateActionAddition() throws Exception {

        }
    };

    Action action = new Action();

    @BeforeEach
    public void initActionHandlerAdapter() {
        action.setJobId(1L).setName("test").setType(ActionType.REMOTE_ACCESS.getType()).setIndex(1)
              .setConsumeDataType(ActionConsumeDataType.DIRECT.getType());
        actionHandlerAdapter.setAction(action);
        ConsumeData consumeData = context.getBean(ActionConsumeDataType.DIRECT.getBeanName(), ConsumeData.class);
        actionHandlerAdapter.setConsumeData(consumeData);
    }

    /**
     * 有占位符但是输入数据类型是String
     */
    @Test
    public void filterAddition() throws Exception {

        ActionTempData<String> input = new ActionTempData<>(ActionStatus.INITIAL, "{'simpleName': \"steven\"}");
        JSONObject addition = JSONObject.parseObject("{'simpleName': \"#{#lastData['simpleName']}\"}");
        action.setAddition(addition);

        ActionTempData<?> actionTempData = actionHandlerAdapter.dataHandler(input);

        assertEquals(addition.getString("simpleName"), "#{#lastData['simpleName']}");
        assertEquals(actionTempData.getBody(), "{'simpleName': \"steven\"}");
    }
}