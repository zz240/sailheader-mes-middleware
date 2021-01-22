package com.sailheader.middleware.action.consume;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionConsumeDataType;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.ActionType;
import com.sailheader.middleware.model.Action;
import org.assertj.core.util.Maps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.JSON_MAPPING_PLACEHOLDER_CANT_PARSE;
import static org.junit.jupiter.api.Assertions.*;

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
     * input 和 action addition 都存在
     * 但是 addition 没有占位符
     */
    @Test
    public void filterAdditionNonPlaceHolder() throws Exception {

        ActionTempData<String> input = new ActionTempData<>(ActionStatus.INITIAL, "{'simpleName': \"steven\"}");
        JSONObject addition = JSONObject.parseObject("{'simpleName': \"steven\"}");
        action.setAddition(addition);

        ActionTempData<?> actionTempData = actionHandlerAdapter.dataHandler(input);

        assertEquals(addition.getString("simpleName"), "steven");
        assertNull(actionTempData.getBody());
    }

    /**
     * 有占位符但是输入数据类型是String
     */
    @Test
    public void filterAdditionInputIsString() {
        ActionTempData<String> input = new ActionTempData<>(ActionStatus.INITIAL, "{'simpleName': \"steven\"}");
        JSONObject addition = JSONObject.parseObject("{'simpleName': \"#{#lastData['simpleName']}\"}");
        action.setAddition(addition);

        MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                               () -> actionHandlerAdapter.dataHandler(input));
        assertEquals(JSON_MAPPING_PLACEHOLDER_CANT_PARSE, middlewareException.getExceptionMap());
    }

    /**
     * 有占位符但是输入数据类型是List
     * TODO 当占位符不是lastData或者currentData时不会被识别也不会报错
     */
    @Test
    public void filterAdditionInputIsList() {
        List<String> arrayList = Collections.singletonList("{'simpleName': 'steven'}");
        ActionTempData<List<String>> input = new ActionTempData<>(ActionStatus.INITIAL, arrayList);
        JSONObject addition = JSONObject.parseObject("{'simpleName': \"#{#lastData['simpleName']}\"}");
        action.setAddition(addition);


        MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                               () -> actionHandlerAdapter.dataHandler(input));
        assertEquals(JSON_MAPPING_PLACEHOLDER_CANT_PARSE, middlewareException.getExceptionMap());
    }

    /**
     * 有占位符但是输入数据类型是List
     * 且占位符通过list获取
     */
    @Test
    public void filterAdditionInputIsListPickUpList() throws Exception {
        List<String> arrayList = Collections.singletonList("steven");
        ActionTempData<List<String>> input = new ActionTempData<>(ActionStatus.INITIAL, arrayList);
        JSONObject addition = JSONObject.parseObject("{'simpleName': \"#{#lastData[0]}\"}");
        action.setAddition(addition);

        actionHandlerAdapter.dataHandler(input);
        assertEquals(action.getAddition().getString("simpleName"), "steven");
    }

    /**
     * 有占位符但是输入数据类型是MAP
     */
    @Test
    public void filterAdditionInputIsMap() throws Exception {
        Map<String, String> map = Maps.newHashMap("simpleName", "steven");
        ActionTempData<Map<String, String>> input = new ActionTempData<>(ActionStatus.INITIAL, map);
        JSONObject addition = JSONObject.parseObject("{'name': \"#{#lastData['simpleName']}\"}");
        action.setAddition(addition);

        actionHandlerAdapter.dataHandler(input);
        assertEquals(action.getAddition().getString("name"), "steven");
    }

    /**
     * 有占位符但是输入数据类型是JSON 且占位符是 xx[0]['xx']
     */
    @Test
    public void filterAdditionInputIsJsonPickUpMixedJson() throws Exception {
        JSON json = (JSON) JSON.parse("[{'simpleName': 'steven'}]");
        ActionTempData<JSON> input = new ActionTempData<>(ActionStatus.INITIAL, json);
        JSONObject addition = JSONObject.parseObject("{'name': \"#{#lastData[0]['simpleName']}\"}");
        action.setAddition(addition);

        actionHandlerAdapter.dataHandler(input);
        assertEquals(action.getAddition().getString("name"), "steven");
    }
}