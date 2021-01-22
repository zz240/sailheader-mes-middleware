package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.ActionType;
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

}
