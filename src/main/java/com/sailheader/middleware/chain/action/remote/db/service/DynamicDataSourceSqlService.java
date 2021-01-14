package com.sailheader.middleware.chain.action.remote.db.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.action.remote.db.dao.DynamicDataSourceSqlDao;
import com.sailheader.middleware.model.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
public class DynamicDataSourceSqlService {

    @Autowired
    private DynamicDataSourceSqlDao dynamicDataSourceSqlDao;

    public JSONObject executeSql(String sql, DataSource dataSource) {
        JSONObject jsonObject;
        try {
            String str = JSON.toJSONString(dynamicDataSourceSqlDao.executeSql(sql));
            jsonObject = (JSONObject) JSONObject.parse(str);
        } catch (Exception e) {
            throw new RuntimeException("无法解析查询内容为JSONObject: " + e.getMessage());
        }
        if (jsonObject == null) {
            throw new RuntimeException("执行sql内容为空!");
        }
        return jsonObject;
    }
}
