package com.sailheader.middleware.action.remote.db;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.model.DataSource;
import org.springframework.stereotype.Component;

/**
 * 根据参数添加数据源
 */

@Component
public class DruidUtil {
    public void switchDB(DataSource data) throws Exception {
        DruidDataSource druidDataSource = new DruidDataSource();
        JSONObject addition = data.getAddition();
        druidDataSource.setUrl(data.getAddress());
        druidDataSource.setDriverClassName((String) addition.get("driver"));
        druidDataSource.setUsername((String) addition.get("userName"));
        druidDataSource.setPassword((String) addition.get("password"));
        try {
            DynamicDataSourceHandler.setDataSource(druidDataSource);
        } catch (Exception e) {
            throw new Exception("未能成功切换数据库!" + data.toString());
        }
    }
}
