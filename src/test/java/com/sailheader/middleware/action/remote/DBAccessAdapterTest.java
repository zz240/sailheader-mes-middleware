package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.remote.db.service.DynamicDataSourceSqlService;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.MiddlewareExceptionMap;
import com.sailheader.middleware.model.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
class DBAccessAdapterTest {
    @Autowired
    DBAccessAdapter dbAccessAdapter;

    JSONObject jsonObject = new JSONObject();
    DataSource dataSource = new DataSource();

    @BeforeEach
    public void initDatasource() {
        jsonObject.put("sql", "select * from action_stream");
        jsonObject.put("driver", "org.postgresql.Driver");
        jsonObject.put("username", "middleware_user");
        jsonObject.put("password", "860fbbdd75de0b5d517323d6f1832e10");

        dataSource.setType(1);
        dataSource.setName("远程访问");
        dataSource.setAddress("jdbc:postgresql://localhost:9001/middleware");
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
            dbAccessAdapter.setDataSource(null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_IS_NULL);
        }

        @Test
        public void testDatasourceAdditionIsNull() {
            dataSource.setAddition(null);
            dbAccessAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_ADDITION_IS_NULL);
        }

        @Test
        public void testDatasourceAddressIsNull() {
            dataSource.setAddress("");
            dbAccessAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourceDriverIsNull() {
            dataSource.getAddition().put("driver", "");
            dbAccessAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourceUsernameIsNull() {
            dataSource.getAddition().put("username", "");
            dbAccessAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testDatasourcePasswordIsNull() {
            dataSource.getAddition().put("password", "");
            dbAccessAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        @Test
        public void testActionTempDataIsNull() {
            dbAccessAdapter.setDataSource(dataSource);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(null));
            assertEquals(middlewareException.getExceptionMap(), ACTION_STATUS_CONFUSE_ACCESS);
        }

        @Test
        public void testActionTempDataStatusIsNull() {
            dbAccessAdapter.setDataSource(dataSource);
            ActionTempData<JSON> actionTempData = new ActionTempData<>(null, null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(actionTempData));
            assertEquals(middlewareException.getExceptionMap(), ACTION_STATUS_CONFUSE_ACCESS);
        }
    }

    /**
     * 检查 sql 替换逻辑
     * 首先在表 data_stream 中新建了一条数据
     * TODO 使用sqy代理
     */
    @Nested
    @DisplayName("check execute sql")
    class CheckDBSql {

        /**
         * 输入数据和数据源都没有sql
         */
        @Test
        public void testSqlIsNull() {
            dataSource.getAddition().put("sql", "");
            dbAccessAdapter.setDataSource(dataSource);
            ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(actionTempData));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_SQL_IS_NULL);
        }

        /**
         * 输入有sql但是数据源没有
         */
        @Test
        public void testInputDataHaveSql() throws Exception {
            // 构造请求
            dataSource.getAddition().put("sql", "");
            dbAccessAdapter.setDataSource(dataSource);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("sql", "select * from action_stream");
            ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, jsonObject);

            JSONArray json = (JSONArray) dbAccessAdapter.access(actionTempData);
            assertEquals(json.size(), 1);
        }

        /**
         * 数据源有sql 但是输入没有sql
         */
        @Test
        public void testDatasourceHaveSql() throws Exception {
            // 构造请求
            dataSource.getAddition().put("sql", "select * from action_stream");
            dbAccessAdapter.setDataSource(dataSource);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("sql", "");
            ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, jsonObject);

            JSONArray json = (JSONArray) dbAccessAdapter.access(actionTempData);
            assertEquals(json.size(), 1);
        }

        /**
         * 数据源和输入都有sql
         */
        @Test
        public void testDatasourceAndInputDataHaveSql() throws Exception {
            // 构造请求
            dataSource.getAddition().put("sql", "select * from data_source");
            dbAccessAdapter.setDataSource(dataSource);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("sql", "select * from action_stream");
            ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, jsonObject);

            JSONArray json = (JSONArray) dbAccessAdapter.access(actionTempData);
            assertEquals(json.size(), 1);
        }
    }

    /**
     * 切换不同类型数据库访问 db
     */
    @Nested
    @DisplayName("switch db type and execute sql")
    class SwitchDifferentDB {

        /**
         * 数据库不存在时
         */
        @Test
        public void testDBConnectNotFound() {
            jsonObject.put("driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
            dataSource.setAddress("jdbc:sqlserver://xxxxxxxx/middleware");

            dbAccessAdapter.setDataSource(dataSource);
            ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                   () -> dbAccessAdapter.access(actionTempData));
            assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_CONNECT_FAILURE);
        }

        /**
         * 增删改查动作操作数据库
         * 返回内容的判定
         * 使用 postgresql 测试
         */
        @Nested
        @DisplayName("curd db then validate result in postgresql")
        @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
        class CURDValidateResult {

            @Test
            @Order(1)
            public void testAdd() throws Exception {
                jsonObject.put("sql",
                               "INSERT INTO public.action_stream (id, job_id, name, type, status, index, temp_data, addition, created_by, created_on,\n" +
                                       "                                  modified_by, modified_on)\n" +
                                       "VALUES (DEFAULT, null, '2', 2, 2, 2, '{\"name\": \"steven\"\n" +
                                       "}', '{\"name\": \"steven\"\n" +
                                       "}', 2, null, null, null);\n");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(actionTempData);
                System.out.println(json);
            }

            @Test
            @Order(2)
            public void testSelect() throws Exception {
                jsonObject.put("sql", "select * from action_stream where name = '2'");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(actionTempData);

                assertEquals(json.size(), 1);
            }

            @Test
            @Order(3)
            public void testUpdate() throws Exception {
                jsonObject.put("sql",
                               "UPDATE public.action_stream\n" +
                                       "SET index = 3\n" +
                                       "WHERE name = '2';\n");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                dbAccessAdapter.access(actionTempData);

                // 验证更新
                jsonObject.put("sql", "select * from action_stream where name = '2'");
                ActionTempData<JSON> validateData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(validateData);

                assertEquals(json.getJSONObject(0).getInteger("index"), 3);
            }

            @Test
            @Order(4)
            public void testDelete() throws Exception {
                jsonObject.put("sql",
                               "DELETE FROM public.action_stream\n" +
                                       "WHERE name = '2';\n");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                dbAccessAdapter.access(actionTempData);

                // 验证删除
                jsonObject.put("sql", "select * from action_stream where name = '2'");
                ActionTempData<JSON> validateData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(validateData);

                assertEquals(json.size(), 0);
            }

            @Test
            public void testTableNotExists() {
                jsonObject.put("sql",
                               "INSERT INTO public.action_stream22 (id, job_id, name, type, status, index, temp_data, addition, created_by, created_on,\n" +
                                       "                                  modified_by, modified_on)\n" +
                                       "VALUES (DEFAULT, null, '2', 2, 2, 2, '{\"name\": \"steven\"\n" +
                                       "}', '{\"name\": \"steven\"\n" +
                                       "}', 2, null, null, null);\n");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                       () -> dbAccessAdapter.access(actionTempData));
                assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_SQL_EXECUTE_FAILURE);
            }

            @Test
            public void testSyntax() throws Exception {
                jsonObject.put("sql",
                               "DELETE 111 FROM public.action_stream\n" +
                                       "WHERE id = 4;\n");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                                                                       () -> dbAccessAdapter.access(actionTempData));
                assertEquals(middlewareException.getExceptionMap(), REMOTE_ACCESS_DB_SQL_EXECUTE_FAILURE);
            }
        }

        /**
         *  使用sql server 测试 crud 动作
         */
        @Nested
        @DisplayName("curd db then validate result in sqlserver")
        @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
        class CURDValidateResultWithSqlSever {

            @BeforeEach
            public void initDatasource() {
                jsonObject.put("sql", "INSERT INTO test VALUES (1, 'banana', 150)");
                jsonObject.put("driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
                jsonObject.put("username", "SA");
                jsonObject.put("password", "Root123456");

                dataSource.setType(1);
                dataSource.setName("远程访问");
                dataSource.setAddress("jdbc:sqlserver://localhost:9002;database=middleware");
                dataSource.setAddition(jsonObject);
            }

            @Test
            @Order(1)
            public void testAdd() throws Exception {
                jsonObject.put("sql", "INSERT INTO test VALUES (1, 'banana', 150)");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(actionTempData);
                System.out.println(json);
            }

            @Test
            @Order(2)
            public void testSelect() throws Exception {
                jsonObject.put("sql", "select * from test where name = 'banana'");
                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(actionTempData);

                assertEquals(json.size(), 1);
            }

            @Test
            @Order(3)
            public void testUpdate() throws Exception {
                jsonObject.put("sql", "update test set quantity = 250 where name = 'banana'");

                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                dbAccessAdapter.access(actionTempData);

                // 验证更新动作
                jsonObject.put("sql", "select * from test where name = 'banana'");
                ActionTempData<JSON> validateData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(validateData);

                assertEquals(json.getJSONObject(0).getInteger("quantity"), 250);

            }

            @Test
            @Order(4)
            public void testDelete() throws Exception {
                jsonObject.put("sql", "delete from test where name = 'banana'");

                dbAccessAdapter.setDataSource(dataSource);
                ActionTempData<JSON> actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
                dbAccessAdapter.access(actionTempData);

                // 验证删除动作
                jsonObject.put("sql", "select * from test where name = 'banana'");
                ActionTempData<JSON> validateData = new ActionTempData<>(ActionStatus.INITIAL, null);
                JSONArray json = (JSONArray) dbAccessAdapter.access(validateData);

                assertEquals(json.size(), 0);
            }

        }
    }

}