package com.sailheader.middleware.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.service.IDataService;
import org.apache.groovy.json.internal.LazyMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DataServiceImplTest {

    @Autowired
    public IDataService iDataService;

    @Test
    void importData() throws Exception {
        final LazyMap normal = (LazyMap) ImportDataJson.getNormal();
        JSONObject map = JSONObject.parseObject(JSON.toJSONString(normal));
        iDataService.importData(map);
    }
}