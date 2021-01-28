package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.DataSource;
import jdk.nashorn.internal.ir.annotations.Ignore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.*;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.REMOTE_ACCESS_DB_SQL_IS_NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

@SpringBootTest
class DBAccessWIthJsonAdapterTest {
    @Autowired
    DBAccessWIthJsonAdapter dbAccessWIthJsonAdapter;

    JSONObject jsonObject = new JSONObject();
    DataSource dataSource = new DataSource();

    @BeforeEach
    public void initDatasource() {
        jsonObject.put("tableName", "json_input_db_test");
        jsonObject.put("driver", "org.postgresql.Driver");
        jsonObject.put("username", "middleware_user");
        jsonObject.put("password", "860fbbdd75de0b5d517323d6f1832e10");

        dataSource.setType(1);
        dataSource.setName("远程访问");
        dataSource.setAddress("jdbc:postgresql://192.168.1.115:9001/middleware");
        dataSource.setAddition(jsonObject);
    }

    /**
     * 检查 datasource db 类型的 addition 验证
     */
    @Nested
    @DisplayName("check db datasource addition param")
    class CheckDBDatasource {

        @Test
        public void testDatasourceIsNull() {
            dbAccessWIthJsonAdapter.setDataSource(null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_IS_NULL);
        }

        @Test
        public void testDatasourceAdditionIsNull() {
            dataSource.setAddition(null);
            dbAccessWIthJsonAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_ADDITION_IS_NULL);
        }

        @Test
        public void testDatasourceAddressIsNull() {
            dataSource.setAddress("");
            dbAccessWIthJsonAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourceDriverIsNull() {
            dataSource.getAddition().put("driver", "");
            dbAccessWIthJsonAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourceUsernameIsNull() {
            dataSource.getAddition().put("username", "");
            dbAccessWIthJsonAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourcePasswordIsNull() {
            dataSource.getAddition().put("password", "");
            dbAccessWIthJsonAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testActionTempDataIsNull() {
            dbAccessWIthJsonAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), ACTION_STATUS_CONFUSE_ACCESS);
        }

        @Test
        public void testActionTempDataStatusIsNull() {
            dbAccessWIthJsonAdapter.setDataSource(dataSource);
            ActionTempData<JSON> actionTempData = new ActionTempData<>(null, null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(actionTempData));
            assertEquals(middlewareException.getExceptionMap(), ACTION_STATUS_CONFUSE_ACCESS);
        }
    }


    @Nested
    @DisplayName("check execute sql")
    @Ignore
    class CheckDBSql {

        /**
         * 该类的测试需要在postgresql 预先执行以下语句：
         *
         *  create table json_input_db_test
         * (
         *  int integer,
         *  varchar varchar,
         *  timestamp timestamp,
         *  decimal decimal
         * );
         *
         */

        /**
         * 测试sql 是否正常执行
         * @throws Exception
         */
        @Test
        public void testSqlNormal() throws Exception {
            BigDecimal decimal = BigDecimal.valueOf(1000);
            Map<String, Object> map = new HashMap<String, Object>();
            dbAccessWIthJsonAdapter.setDataSource(dataSource);

            map.put("int", 2);
            map.put("varchar", "StringTest");
            map.put("timestamp", new Date());
            map.put("decimal", decimal);

            List list = new ArrayList();
            list.add(map);

            ActionTempData<JSON> actionTempData = new ActionTempData(ActionStatus.INITIAL, list);
            JSONObject json = (JSONObject) dbAccessWIthJsonAdapter.access(actionTempData);
            assertEquals(json.getInteger("count"), 1);
        }

        /**
         * 测试sql 部分字段 为空
         * @throws Exception
         */
        @Test
        public void testSqlPartFieldIsNull() throws Exception {
            Map<String, Object> map = new HashMap();
            dbAccessWIthJsonAdapter.setDataSource(dataSource);

            map.put("int", null);
            map.put("varchar", null);
            map.put("timestamp", new Date());
            map.put("decimal", null);

            List list = new ArrayList();
            list.add(map);

            ActionTempData<JSON> actionTempData = new ActionTempData(ActionStatus.INITIAL, list);
            JSONObject json = (JSONObject) dbAccessWIthJsonAdapter.access(actionTempData);
            assertEquals(json.getInteger("count"), 1);
        }

        /**
         * 测试 sql 全部为空
         * @throws Exception
         */
        @Test
        public void testSqlFieldIsNull() throws Exception {
            Map<String, Object> map = new HashMap();
            dbAccessWIthJsonAdapter.setDataSource(dataSource);

            List list = new ArrayList();
            list.add(map);  // map为空

            ActionTempData<JSON> actionTempData = new ActionTempData(ActionStatus.INITIAL, list);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(actionTempData));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_SQL_EXECUTE_FAILURE);
        }

        /**
         * 测试 sql 类型输入异常
         * @throws Exception
         */
        @Test
        public void testSqlFieldTypeException() throws Exception {
            Map<String, Object> map = new HashMap();
            dbAccessWIthJsonAdapter.setDataSource(dataSource);

            map.put("int", 123);
            map.put("varchar", "String");
            map.put("timestamp", "String");    // 日期类型异常
            map.put("decimal", 1000);

            List list = new ArrayList();
            list.add(map);

            ActionTempData<JSON> actionTempData = new ActionTempData(ActionStatus.INITIAL, list);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> dbAccessWIthJsonAdapter.access(actionTempData));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_SQL_EXECUTE_FAILURE);
        }

    }

}