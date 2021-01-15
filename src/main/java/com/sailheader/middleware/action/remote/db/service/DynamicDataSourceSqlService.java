package com.sailheader.middleware.action.remote.db.service;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.action.remote.db.dao.DynamicDataSourceSqlDao;
import com.sailheader.middleware.model.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DynamicDataSourceSqlService {

    @Autowired
    private DynamicDataSourceSqlDao dynamicDataSourceSqlDao;

    public JSON executeSql(String sql, DataSource dataSource) {
        JSON json;
        try {
            json = (JSON) JSON.toJSON(dynamicDataSourceSqlDao.executeSql(sql));
        } catch (Exception e) {
            throw new RuntimeException("无法解析查询结果转化为json: " + e.getMessage());
        }
        if (json == null) {
            throw new RuntimeException("返回的json为空!");
        }
        return json;
    }
}
