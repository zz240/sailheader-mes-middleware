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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 补全自身内容的消费数据方式测试
 */
@SpringBootTest
class ComplementConsumeTests {

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
              .setConsumeDataType(ActionConsumeDataType.COMPLETION.getType());
        actionHandlerAdapter.setAction(action);
        ConsumeData consumeData = context.getBean(ActionConsumeDataType.COMPLETION.getBeanName(), ConsumeData.class);
        actionHandlerAdapter.setConsumeData(consumeData);
    }

    /**
     * 不接收上一个动作的数据
     */
    @Test
    public void refuseInputDataAction() throws Exception {

        ActionTempData<String> input = new ActionTempData<>(ActionStatus.INITIAL, "{'simpleName': \"steven\"}");
        JSONObject addition = JSONObject.parseObject("{'simpleName': \"steven\"}");
        action.setAddition(addition);

        ActionTempData<?> actionTempData = actionHandlerAdapter.dataHandler(input);

        assertEquals(addition.getString("simpleName"), "steven");
        assertNull(actionTempData.getBody());
    }


}