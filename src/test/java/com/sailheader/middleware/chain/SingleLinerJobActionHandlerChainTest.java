package com.sailheader.middleware.chain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
import org.assertj.core.util.Maps;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.GROOVY_JSON_MAPPING_PARSE_ERROR;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.JSON_MAPPING_PLACEHOLDER_CANT_PARSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class SingleLinerJobActionHandlerChainTest {

    @Autowired
    ApplicationContext applicationContext;
    SingleLinerJobActionHandlerChain singleLinerJobActionHandlerChain;
    ActionHandlerAdapter<JSON, JSON> actionHandlerAdapter1;
    ActionHandlerAdapter<JSON, JSON> actionHandlerAdapter2;
    Action action1;
    Action action2;

    /**
     * 初始化动作和数据
     */
    @BeforeEach
    public void initData() {
        singleLinerJobActionHandlerChain = new SingleLinerJobActionHandlerChain(new ArrayList<>());

        // 第一种动作
        actionHandlerAdapter1 = new ActionHandlerAdapter<JSON, JSON>() {
            @Override
            protected ActionTempData<JSON> execute(ActionTempData<JSON> object) throws Exception {
                object.setStatus(ActionStatus.SUCCESS);
                object.setBody(this.action.getAddition());
                return object;
            }

            @Override
            protected void validateActionAddition() throws Exception {

            }
        };

        // 第二种动作
        actionHandlerAdapter2 = new ActionHandlerAdapter<JSON, JSON>() {
            @Override
            protected ActionTempData<JSON> execute(ActionTempData<JSON> object) throws Exception {
                object.setStatus(ActionStatus.SUCCESS);
                object.setBody(this.action.getAddition());
                return object;
            }

            @Override
            protected void validateActionAddition() throws Exception {

            }
        };

        action1 = new Action();
        action2 = new Action();
        action1.setJobId(1L).setName("test1").setType((short) 1).setIndex(1);
        action2.setJobId(1L).setName("test2").setType((short) 1).setIndex(2);

        actionHandlerAdapter1.setAction(action1);
        actionHandlerAdapter2.setAction(action2);

        singleLinerJobActionHandlerChain = new SingleLinerJobActionHandlerChain(new ArrayList<>());
        singleLinerJobActionHandlerChain.addAction(actionHandlerAdapter1);
        singleLinerJobActionHandlerChain.addAction(actionHandlerAdapter2);
    }

    /**
     * 测试在单链执行之前的预处理
     */
    @Nested
    @DisplayName("check prepare action init")
    class preActionData {
        /**
         * 存在占位符
         * 但是输入数据是一个string
         */
        @Test
        public void filterAdditionInputIsString() throws Exception {
            // 输入数据
            ActionTempData<String> input = new ActionTempData<>(ActionStatus.INITIAL, "{'simpleName': \"steven\"}");
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body.simpleName\" }");
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.1.body.simpleName\"}");
            action2.setAddition(addition2);

            // 执行动作链
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> singleLinerJobActionHandlerChain.dataHandler(input));
            assertEquals(GROOVY_JSON_MAPPING_PARSE_ERROR, middlewareException.getExceptionMap());
        }

        /**
         * 有占位符但是输入数据类型是List
         * TODO 当占位符不是lastData或者currentData时不会被识别也不会报错
         */
        @Test
        public void filterAdditionInputIsList() {
            List<String> arrayList = Collections.singletonList("{'simpleName': 'steven'}");
            // 输入数据
            ActionTempData<List<String>> input = new ActionTempData<>(ActionStatus.INITIAL, arrayList);
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body.simpleName\" }");
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.1.body.simpleName\"}");
            action2.setAddition(addition2);

            // 执行动作链
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> singleLinerJobActionHandlerChain.dataHandler(input));
            assertEquals(GROOVY_JSON_MAPPING_PARSE_ERROR, middlewareException.getExceptionMap());
        }

        /**
         * 有占位符但是输入数据类型是List
         * 且占位符通过list获取
         */
        @Test
        public void filterAdditionInputIsListPickUpList() throws Exception {
            List<String> arrayList = Collections.singletonList("steven");
            // 输入数据
            ActionTempData<List<String>> input = new ActionTempData<>(ActionStatus.INITIAL, arrayList);
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body.0\" }");
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.1.body.simpleName\"}");
            action2.setAddition(addition2);

            // 执行动作链
            ActionTempData<?> actionTempData = singleLinerJobActionHandlerChain.dataHandler(input);

            assertEquals(actionHandlerAdapter2.getAction().getAddition().getString("simpleName"), "steven");
        }

        /**
         * 有占位符但是输入数据类型是MAP
         */
        @Test
        public void filterAdditionInputIsMap() throws Exception {
            Map<String, String> map = Maps.newHashMap("simpleName", "steven");
            // 输入数据
            ActionTempData<Map<String, String>> input = new ActionTempData<>(ActionStatus.INITIAL, map);
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body.simpleName\" }");
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.1.body.simpleName\"}");
            action2.setAddition(addition2);

            // 执行动作链
            ActionTempData<?> actionTempData = singleLinerJobActionHandlerChain.dataHandler(input);

            assertEquals(actionHandlerAdapter2.getAction().getAddition().getString("simpleName"), "steven");
        }

        /**
         * 有占位符但是输入数据类型是List
         * 查看list是否被替换
         */
        @Test
        public void filterAdditionInputReplaceList() throws Exception {
            List<String> arrayList = Collections.singletonList("steven");
            // 输入数据
            ActionTempData<List<String>> input = new ActionTempData<>(ActionStatus.INITIAL, arrayList);
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body\" }");
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.1.body.simpleName\"}");
            action2.setAddition(addition2);

            // 执行动作链
            ActionTempData<?> actionTempData = singleLinerJobActionHandlerChain.dataHandler(input);

            assertEquals(actionHandlerAdapter2.getAction().getAddition().getJSONArray("simpleName"), arrayList);
        }

        /**
         * 有占位符但是输入数据类型是MAP
         */
        @Test
        public void filterAdditionInputReplaceMap() throws Exception {
            Map<String, String> map = Maps.newHashMap("simpleName", "steven");
            // 输入数据
            ActionTempData<Map<String, String>> input = new ActionTempData<>(ActionStatus.INITIAL, map);
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body\" }");
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.1.body.simpleName\"}");
            action2.setAddition(addition2);

            // 执行动作链
            ActionTempData<?> actionTempData = singleLinerJobActionHandlerChain.dataHandler(input);

            assertEquals(actionHandlerAdapter2.getAction().getAddition().getJSONObject("simpleName"), map);
        }

        /**
         * 有占位符但是输入数据类型是JSON 且占位符是 xx[0]['xx']
         */
        @Test
        public void filterAdditionInputIsJsonPickUpMixedJson() throws Exception {
            JSON json = (JSON) JSON.parse("[{'simpleName': 'steven'}]");
            // 输入数据
            ActionTempData<JSON> input = new ActionTempData<>(ActionStatus.INITIAL, json);
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body.0.simpleName\" }");
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.1.body.simpleName\"}");
            action2.setAddition(addition2);

            // 执行动作链
            ActionTempData<?> actionTempData = singleLinerJobActionHandlerChain.dataHandler(input);

            assertEquals(actionHandlerAdapter2.getAction().getAddition().getString("simpleName"), "steven");
        }

        /**
         * 自定义index访问
         */
        @Test
        public void filterAdditionInputCustomIndex() throws Exception {
            JSON json = (JSON) JSON.parse("[{'simpleName': 'steven'}]");
            // 输入数据
            ActionTempData<JSON> input = new ActionTempData<>(ActionStatus.INITIAL, json);
            // 动作1的 action addition
            JSONObject addition1 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.0.body.0.simpleName\" }");
            action1.setIndex(2);
            action1.setAddition(addition1);

            // 动作2的 action addition
            JSONObject addition2 = JSONObject
                    .parseObject("{'simpleName': \"$contextData.2.body.simpleName\"}");
            action2.setIndex(5);
            action2.setAddition(addition2);

            // 执行动作链
            ActionTempData<?> actionTempData = singleLinerJobActionHandlerChain.dataHandler(input);

            assertEquals(actionHandlerAdapter2.getAction().getAddition().getString("simpleName"), "steven");
        }
    }
}