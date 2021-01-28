package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.MiddlewareExceptionMap;
import com.sailheader.middleware.model.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

@SpringBootTest
class RemoteAccessActionHandlerTests {

    @Autowired
    RemoteAccessActionHandler remoteAccessActionHandler;

    Action action = new Action().setJobId(1L).setName("test").setType((short) 1).setIndex(1);

    /**
     * 检查 datasource 有效性
     */
    @Nested
    @DisplayName("check action addition datasource effectiveness")
    class CheckDataSource {
        JSONObject jsonObject;

        @BeforeEach
        public void initJsonObject() {
            jsonObject = new JSONObject();
            jsonObject.put("name", "远程访问");
            jsonObject.put("type", 1);
            jsonObject.put("address", "https://");
            jsonObject.put("addition", new JSONObject());
        }

        @Test
        public void testDataSourceIsNull() {
            action.setAddition(null);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_DATASOURCE_IS_NULL);
        }

        /**
         * json 字段类型和 datasource 类型不符
         */
        @Test
        public void testDataSourceParseError() {
            jsonObject.put("type", "db");
            action.setAddition(jsonObject);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_DATASOURCE_PARSE_ERROR);
        }

        @Test
        public void testDataSourceNonName() {
            action.setAddition(new JSONObject());
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDataSourceNonType() {
            jsonObject.put("type", "");
            action.setAddition(jsonObject);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDataSourceNonAddress() {
            jsonObject.put("address", "");
            action.setAddition(jsonObject);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDataSourceNonAddition() {
            jsonObject.put("addition", null);
            action.setAddition(jsonObject);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM);
        }
    }

    @Nested
    @DisplayName("checkout spring bean")
    class CheckoutBean {
        JSONObject datasourceJson;

        @BeforeEach
        public void init() {
            datasourceJson = new JSONObject();
            datasourceJson.put("name", "远程访问");
            datasourceJson.put("type", 1);
            datasourceJson.put("address", "https://");
            datasourceJson.put("addition", new JSONObject());
        }

        /**
         * 在 map 中没有声明 bean 的id
         */
        @Test
        public void testNonBeanInMap() {
            datasourceJson.put("type", 999);
            action.setAddition(datasourceJson);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_ACCESS_BEAN_NOT_FOUND);
        }

        /**
         * 在 spring 上下文中没有找到 bean
         */
        @Test
        public void testNonBeanInSpringContext() {
            datasourceJson.put("type", 100);
            action.setAddition(datasourceJson);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                         REMOTE_ACCESS_ACTION_ACCESS_BEAN_INIT_FAILURE);
        }

        /**
         * 测试 addition 解析
         * 单层结构
         * todo
         */
        @Test
        public void testDataSourceHasAddition() {
            // 构造addition
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("method", "get");
            datasourceJson.put("addition", jsonObject);

            action.setAddition(datasourceJson);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                    ACTION_STATUS_CONFUSE_ACCESS);
        }

        /**
         * 测试 addition 解析
         * 单层结构
         */
        @Test
        public void testDataSourceHasAdditionMul() {
            // 构造addition
            JSONObject jsonObject = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("content-type", "application/json");
            jsonObject.put("header", header);
            datasourceJson.put("addition", jsonObject);

            action.setAddition(datasourceJson);
            remoteAccessActionHandler.setAction(action);

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> remoteAccessActionHandler.dataHandler(null));
            assertEquals(middlewareException.getExceptionMap(),
                    REMOTE_ACCESS_HTTP_MISSING_REQUIRE_PARAM);
        }
    }

    // 冒烟需要具体动作测试 mock todo

}