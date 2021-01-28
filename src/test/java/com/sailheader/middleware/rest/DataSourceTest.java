package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.rest.DataSourceController;
import jdk.nashorn.internal.ir.annotations.Ignore;
import org.junit.jupiter.api.Test;
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

    //  新增数据源 - 完整流程
    @Test
    public void createDataSourceTest(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        System.out.println(json);
        dataSource.setName("dataSourceTest01").setAddress("createDataSourceTest").setType(1).setCreatedBy(2);
        dataSource.setAddition(JSONObject.parseObject(json));
        OperationResult result = dataSourceController.createDataSource(dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.ADD_SUCCESS.getValue());
    }

    //  新增数据源 - 缺少名称
    @Test
    public void createDataSourceTest01(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setAddition(JSONObject.parseObject(json))
                .setAddress("ewqewqewq").setType(1).setCreatedBy(2);
        OperationResult result = dataSourceController.createDataSource(dataSource);
        System.out.println(result);

        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());

    }

    //  新增数据源 - 重复名称
    @Test
    public void createDataSourceTest02(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("dataSourceTest01").setAddress("ewqewqewq").setType(1).setCreatedBy(2);
        dataSource.setAddition(JSONObject.parseObject(json));
        OperationResult result = dataSourceController.createDataSource(dataSource);
        System.out.println(result);

        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
    }

    //  新增数据源 - 缺少type
    @Test
    public void createDataSourceTest03(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("dataSourceTest03").setAddress("testType01").setCreatedBy(2);
        dataSource.setAddition(JSONObject.parseObject(json));
        OperationResult result = dataSourceController.createDataSource(dataSource);
        System.out.println(result);

        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
    }

    //  新增数据源 - 缺少address
    @Test
    public void createDataSourceTest04(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("dataSourceTest04").setType(1).setCreatedBy(2);
        dataSource.setAddition(JSONObject.parseObject(json));
        OperationResult result = dataSourceController.createDataSource(dataSource);
        System.out.println(result);

        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
    }

    //  新增数据源 - 缺少addition
    @Test
    public void createDataSourceTest05(){
        dataSource.setName("dataSourceTest05").setAddress("testaddress").setType(2).setCreatedBy(1);
        OperationResult result = dataSourceController.createDataSource(dataSource);
        System.out.println(result);

        assertEquals(result.getStateCode(), StateCode.ADD_FAIL.getValue());
    }

    // 删除数据源 - 正确流程
    @Test
    public void deleteDataSourceTest01(){
        result = dataSourceController.deleteDataSource((long) 5);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.DELETE_SUCCESS.getValue());
    }

    // 删除数据源 - 已删除数据源
    @Test
    public void deleteDataSourceTest02(){
        result = dataSourceController.deleteDataSource((long) 3);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
    }

    // 删除数据源 - 范围之外的id
    @Test
    public void deleteDataSourceTest03(){
        result = dataSourceController.deleteDataSource((long) 1000);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.DELETE_FAIL.getValue());
    }

    // 查询数据源 - 正确流程
    @Test
    public void findDataSourceTest01(){
        result = dataSourceController.findDataSourceById((long) 13);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.QUERY_SUCCESS.getValue());
    }

    // 查询数据源 - 范围之外的id
    @Test
    public void findDataSourceTest02(){
        result = dataSourceController.findDataSourceById((long) 123);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
    }

    // 查询数据源 - 已删除的数据源
    @Test
    public void findDataSourceTest03(){
        result = dataSourceController.findDataSourceById((long) 5);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.QUERY_FAIL.getValue());
    }


    //更新数据源 - 正确流程
    @Test
    public void updateDataSourceTest01(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("updateDataSourceTest011").setAddition(JSONObject.parseObject(json)).setType(1).setModifiedBy(1)
        .setAddress("DataSourceTest01Address");
        result = dataSourceController.updateDataSource((long)4,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_SUCCESS.getValue());
    }

    //更新数据源 - 缺少 name
    @Test
    public void updateDataSourceTest02(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setAddition(JSONObject.parseObject(json)).setType(1).setModifiedBy(1)
                .setAddress("DataSourceTest01Address");
        result = dataSourceController.updateDataSource((long)4,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    //更新数据源 - 缺少 type
    @Test
    public void updateDataSourceTest03(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("updateDataSourceTest03").setAddition(JSONObject.parseObject(json)).setModifiedBy(1)
                .setAddress("DataSourceTest01Address");
        result = dataSourceController.updateDataSource((long)4,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    //更新数据源 - 缺少 address
    @Test
    public void updateDataSourceTest04(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("updateDataSourceTest04").setAddition(JSONObject.parseObject(json)).setType(1).setModifiedBy(1);
        result = dataSourceController.updateDataSource((long)4,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    //更新数据源 - 缺少 addition
    @Test
    public void updateDataSourceTest05(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("updateDataSourceTest05").setType(1).setModifiedBy(1)
                .setAddress("DataSourceTest01Address");
        result = dataSourceController.updateDataSource((long)4,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    //更新数据源 - 更新的id已删除
    @Test
    public void updateDataSourceTest06(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("updateDataSourceTest06").setAddition(JSONObject.parseObject(json)).setType(1).setModifiedBy(1)
                .setAddress("DataSourceTest01Address");
        result = dataSourceController.updateDataSource((long)5,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    //更新数据源 - 更新的id不存在
    @Test
    public void updateDataSourceTest07(){
        String json = "{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("updateDataSourceTest07").setAddition(JSONObject.parseObject(json)).setType(1).setModifiedBy(1)
                .setAddress("DataSourceTest01Address");
        result = dataSourceController.updateDataSource((long)1115,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

    //更新数据源 - addition 插入异常
    @Test
    public void updateDataSourceTest08(){
        String json = "1{\"id\":3,\"addition\":{\"address\":\"http://localhost:3361/test\",\"method\":" +
                "\"GET\",\"query\":\"\",\"header\":{\"Accept\":[\"text/html\",\"application/xml\"],\"Accept-Encoding\":[\"gzip\"]},\"body\":{\"userName\":\"TEST\"}}}";
        dataSource.setName("updateDataSourceTest08").setAddition(JSONObject.parseObject(json)).setType(1).setModifiedBy(1)
                .setAddress("DataSourceTest01Address");
        result = dataSourceController.updateDataSource((long)4,dataSource);
        System.out.println(result);
        assertEquals(result.getStateCode(), StateCode.EDIT_FAIL.getValue());
    }

}
