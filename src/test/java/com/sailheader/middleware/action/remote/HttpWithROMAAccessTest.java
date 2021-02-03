package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.ACTION_STATUS_CONFUSE_ACCESS;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HttpWithROMAAccessTest {
    @Autowired
    HttpWithROMAAccess httpWithROMAAccess;

    JSONObject jsonObject = new JSONObject();
    DataSource dataSource = new DataSource();
    JSONObject query = new JSONObject();

    @BeforeEach
    public void initDatasource() {
        query.put("code", "WMPurchaseOrderDetail");

        jsonObject.put("key", "94a4d318-7aa4-406e-964a-70f48cb5da9c");
        jsonObject.put("secret", "47e7fd746471f97848dd2464914d7d7e23d564f66fcf0665e06352be8b45a646");
        jsonObject.put("method", "POST");
        jsonObject.put("query", query);

        dataSource.setType(4);
        dataSource.setName("http ROMA test");
        dataSource.setAddress("https://fc1b0aacc4ab4778831e61aafd716953.apic.cn-south-1.huaweicloudapis.com/mes/cloud/v1/module/data-import/api/data/import/main/execute/synchronization");
        dataSource.setAddition(jsonObject);
    }

    /**
     * 检查 datasource http roma 类型的 addition 验证
     */
    @Nested
    @DisplayName("check db datasource addition param")
    class CheckDBDatasource {

        @Test
        public void testDatasourceIsNull() {
            httpWithROMAAccess.setDataSource(null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_IS_NULL);
        }

        @Test
        public void testDatasourceAdditionIsNull() {
            dataSource.setAddition(null);
            httpWithROMAAccess.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_ADDITION_IS_NULL);
        }

        @Test
        public void testDatasourceAddressIsNull() {
            dataSource.setAddress("");
            httpWithROMAAccess.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourceKeyIsNull() {
            dataSource.getAddition().put("key", "");
            httpWithROMAAccess.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourceSecretIsNull() {
            dataSource.getAddition().put("secret", "");
            httpWithROMAAccess.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourceMethodIsNull() {
            dataSource.getAddition().put("method", "");
            httpWithROMAAccess.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testActionTempDataIsNull() {
            httpWithROMAAccess.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(null));
            assertEquals(middlewareException.getExceptionMap(), ACTION_STATUS_CONFUSE_ACCESS);
        }

        @Test
        public void testActionTempDataStatusIsNull() {
            httpWithROMAAccess.setDataSource(dataSource);
            ActionTempData<JSON> actionTempData = new ActionTempData<>(null, null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> httpWithROMAAccess.access(actionTempData));
            assertEquals(middlewareException.getExceptionMap(), ACTION_STATUS_CONFUSE_ACCESS);
        }
    }

    // 冒烟测试
    @Test
    public void testNormal() throws Exception {
        httpWithROMAAccess.setDataSource(dataSource);
        JSONObject access = (JSONObject) httpWithROMAAccess.access(new ActionTempData<>(ActionStatus.INITIAL, null));
        System.out.println(access);
        assertEquals(access.getString("message"), "sueecss");
    }
}