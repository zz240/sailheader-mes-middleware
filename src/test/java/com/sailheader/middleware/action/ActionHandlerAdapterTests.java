package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
import org.assertj.core.util.Maps;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试单个动作数据注入并输出流程
 */
@SpringBootTest
public class ActionHandlerAdapterTests {

    /**
     * action param 参数验证
     */
    @Nested
    @DisplayName("action param tests")
    class ActionParamTests {
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
        public void initAction() {
            action.setJobId(1L).setName("test").setType((short) 1).setIndex(1);
        }

        @Test
        public void testActionDataIsEmpty() {
            actionHandlerAdapter.setAction(null);
            MiddlewareException exception = assertThrows(MiddlewareException.class,
                                                         () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_IS_NULL, exception.getExceptionMap());
        }

        @Test
        public void testActionDataIsNull() {
            actionHandlerAdapter.setAction(new Action());
            MiddlewareException exception = assertThrows(MiddlewareException.class,
                                                         () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception.getExceptionMap());
        }

        @Test
        public void testActionDataWithJogId() {
            action.setJobId(0L);
            actionHandlerAdapter.setAction(action);
            MiddlewareException exception1 = assertThrows(MiddlewareException.class,
                                                          () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception1.getExceptionMap());

            action.setJobId(null);
            actionHandlerAdapter.setAction(action);
            MiddlewareException exception2 = assertThrows(MiddlewareException.class,
                                                          () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception2.getExceptionMap());
        }

        @Test
        public void testActionDataWithName() {
            action.setName(null);
            actionHandlerAdapter.setAction(action);
            MiddlewareException exception1 = assertThrows(MiddlewareException.class,
                                                          () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception1.getExceptionMap());

            action.setJobId(1L).setName("");
            actionHandlerAdapter.setAction(action);
            MiddlewareException exception2 = assertThrows(MiddlewareException.class,
                                                          () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception2.getExceptionMap());
        }

        @Test
        public void testActionDataWithType() {
            action.setType(null);
            actionHandlerAdapter.setAction(action);
            MiddlewareException exception1 = assertThrows(MiddlewareException.class,
                                                          () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception1.getExceptionMap());

            action.setJobId(1L).setName("test").setType((short) 0);
            actionHandlerAdapter.setAction(action);
            MiddlewareException exception2 = assertThrows(MiddlewareException.class,
                                                          () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception2.getExceptionMap());
        }

        @Test
        public void testActionDataWithIndex() {
            action.setIndex(0);
            actionHandlerAdapter.setAction(action);
            MiddlewareException exception1 = assertThrows(MiddlewareException.class,
                                                          () -> actionHandlerAdapter.dataHandler(null));
            assertEquals(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM, exception1.getExceptionMap());
        }
    }

    /**
     * 占位符替换测试
     */
    @Nested
    @DisplayName("filter action addition and temp data")
    class FilterJsonData {
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

        Action action = new Action().setJobId(1L).setName("test").setType((short) 1).setIndex(1);

        {
            actionHandlerAdapter.setAction(action);
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

            actionHandlerAdapter.dataHandler(input);

            assertEquals(addition.getString("simpleName"), "steven");
        }

        /**
         * 有占位符但是输入数据类型是String
         * TODO fastjson迭代转换
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

        /**
         * 检验设置输出格式是否能被 currentData 替换
         */
        @Test
        public void filterOutDataCurrentData() throws Exception {
            JSON json = (JSON) JSON.parse("[{'simpleName': 'steven'}]");
            ActionTempData<JSON> input = new ActionTempData<>(ActionStatus.INITIAL, json);
            JSONObject tempData = JSONObject.parseObject("{'name': \"#{#current[0]['simpleName']}\"}");
            action.setTempData(tempData);

            ActionTempData<JSON> result = actionHandlerAdapter.dataHandler(input);
            assertEquals(JSONObject.parseObject(result.getBody().toJSONString()).getString("name"), "steven");
        }

    }

}
