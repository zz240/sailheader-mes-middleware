package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.rest.DataSourceController;
import jdk.nashorn.internal.ir.annotations.Ignore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Ignore
public class DataSourceTest {
    @Autowired
    private DataSourceController dataSourceController;
    OperationResult result = new OperationResult();
    DataSource dataSource = new DataSource();

    @Nested
    @DisplayName("check Create Action Tests")
    class checkCreateDataSource {

        @BeforeEach
        public void initDataSource() {
            int i = 10;
            String name = "test" + i++;
            String json = "{\"id\":1,\"name\":\"" + name + "\",\"type\":1,\"address\":\"http://localhost:3361/test\",\"addition\":{\"method\":\"GET\",\"query\":\"\",\"header\":{\"Content-type\":[\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
            System.out.println(json);
            dataSource.setName("dataSourceTest" + i).setAddress("createDataSourceTest").setType(1).setCreatedBy(2);
            dataSource.setAddition(JSONObject.parseObject(json));
        }

        //  新增数据源 - 完整流程
        @Test
        @Order(1)
        public void createDataSourceTest() {
            OperationResult result = dataSourceController.createDataSource(dataSource);
            System.out.println(result);
            assertEquals(StateCode.SYSTEM_SUCCESS.getValue(), result.getStateCode());
        }

        //  新增数据源 - 缺少名称
        @Test
        @Order(2)
        public void createDataSourceTest01() {
            dataSource.setName(null);
            OperationResult result = dataSourceController.createDataSource(dataSource);
            System.out.println(result);
            assertEquals("数据源名称为空", result.getMessage());

        }

        //  新增数据源 - 重复名称
        @Test
        @Order(3)
        public void createDataSourceTest02() {
            dataSource.setName("22");
            OperationResult result = dataSourceController.createDataSource(dataSource);
            System.out.println(result);

            assertEquals("数据源名称重复", result.getMessage());
        }

        //  新增数据源 - 缺少type
        @Test
        @Order(4)
        public void createDataSourceTest03() {
            dataSource.setType(null);
            OperationResult result = dataSourceController.createDataSource(dataSource);
            System.out.println(result);

            assertEquals("数据源 type 为空", result.getMessage());
        }

        //  新增数据源 - 缺少address
        @Test
        @Order(5)
        public void createDataSourceTest04() {
            dataSource.setAddress(null);
            OperationResult result = dataSourceController.createDataSource(dataSource);
            System.out.println(result);

            assertEquals("数据源 address 为空", result.getMessage());
        }

        //  新增数据源 - 缺少addition
        @Test
        @Order(6)
        public void createDataSourceTest05() {
            dataSource.setAddition(null);
            OperationResult result = dataSourceController.createDataSource(dataSource);
            System.out.println(result);

            assertEquals("数据源 addition 为空", result.getMessage());
        }
    }

    @Nested
    @DisplayName("check delete Action Tests")
    class checkDeleteDataSource {
        /**
         * 先添加一个datasource，再将动作的id设置在参数中
         */


        // 删除数据源 - 正确流程
        @Test
        @Order(1)
        public void deleteDataSourceTest01() {
            result = dataSourceController.deleteDataSource((long) 1);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        // 删除数据源 - 已删除数据源
        @Test
        @Order(2)
        public void deleteDataSourceTest02() {
            result = dataSourceController.deleteDataSource((long) 3);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
        }

        // 删除数据源 - 范围之外的id
        @Test
        @Order(3)
        public void deleteDataSourceTest03() {
            result = dataSourceController.deleteDataSource((long) 1000);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
        }
    }


    @Nested
    @DisplayName("check find datasource Tests")
    class checkFindDataSource {
        // 查询数据源 - 正确流程
        @Test
        @Order(1)
        public void findDataSourceTest01() {
            result = dataSourceController.findDataSourceById((long) 23);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        // 查询数据源 - 范围之外的id
        @Test
        @Order(2)
        public void findDataSourceTest02() {
            result = dataSourceController.findDataSourceById((long) 10000);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
        }

        // 查询数据源 - 已删除的数据源
        @Test
        @Order(3)
        public void findDataSourceTest03() {
            result = dataSourceController.findDataSourceById((long) 1);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
        }
    }

    @Nested
    @DisplayName("check update datasource Tests")
    class checkUpdateDataSource{
        @BeforeEach
        public void initDataSource(){
            int i = 20;
            i++;
            String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                    "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/json\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
            dataSource.setName("updateDataSourceTest"+i)
                    .setAddition(JSONObject.parseObject(json))
                    .setType(1)
                    .setModifiedBy(1)
                    .setAddress("DataSourceTest01Address");
        }

        //更新数据源 - 正确流程
        @Test
        @Order(1)
        public void updateDataSourceTest01() {
            result = dataSourceController.updateDataSource((long) 4, dataSource);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.SYSTEM_SUCCESS.getValue());
        }

        //更新数据源 - 缺少 name
        @Test
        @Order(2)
        public void updateDataSourceTest02() {
            dataSource.setName(null);
            result = dataSourceController.updateDataSource((long) 4, dataSource);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        //更新数据源 - 缺少 type
        @Test
        @Order(3)
        public void updateDataSourceTest03() {
            dataSource.setType(null);
            result = dataSourceController.updateDataSource((long) 4, dataSource);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        //更新数据源 - 缺少 address
        @Test
        @Order(4)
        public void updateDataSourceTest04() {
            dataSource.setAddress(null);
            result = dataSourceController.updateDataSource((long) 4, dataSource);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        //更新数据源 - 缺少 addition
        @Test
        @Order(5)
        public void updateDataSourceTest05() {
            dataSource.setAddition(null);
            result = dataSourceController.updateDataSource((long) 4, dataSource);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        //更新数据源 - 更新的id已删除
        @Test
        @Order(6)
        public void updateDataSourceTest06() {
            result = dataSourceController.updateDataSource((long) 1, dataSource);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }

        //更新数据源 - 更新的id不存在
        @Test
        @Order(7)
        public void updateDataSourceTest07() {
            result = dataSourceController.updateDataSource((long) 10000, dataSource);
            System.out.println(result);
            assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
        }


        //更新数据源 - 重复 name
        @Test
        @Order(8)
        public void updateDataSourceTest08() {
            dataSource.setName("demo01");
            result = dataSourceController.updateDataSource((long) 4, dataSource);
            System.out.println(result);
            assertEquals( StateCode.EDIT_FAIL.getValue(),result.getStateCode());
        }
    }
}
