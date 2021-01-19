package com.sailheader.middleware.action.remote.db;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.MiddlewareExceptionMap;
import com.sailheader.middleware.model.DataSource;
import org.springframework.stereotype.Component;

/**
 * 根据参数添加数据源
 */

@Component
public class DruidUtil {
    public void switchDB(DataSource data) throws Exception {
        DruidDataSource druidDataSource = new DruidDataSource();
        setDefaultParam(druidDataSource);
        JSONObject addition = data.getAddition();
        druidDataSource.setUrl(data.getAddress());
        druidDataSource.setDriverClassName(addition.getString("driver"));
        druidDataSource.setUsername(addition.getString("username"));
        druidDataSource.setPassword(addition.getString("password"));
        try {
            DynamicDataSourceHandler.setDataSource(druidDataSource);
        } catch (Exception e) {
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.REMOTE_ACCESS_DB_SWATCH_FAILURE, e);
        }
    }

    private void setDefaultParam(DruidDataSource druidDataSource) {
        druidDataSource.setInitialSize(1); // 初始化连接大小
        druidDataSource.setMinIdle(1); // 最小连接池数量
        druidDataSource.setMaxActive(1); // 最大连接池数量
        druidDataSource.setMaxWait(60000); // 获取连接时最大等待时间，单位毫秒
        druidDataSource.setKeepAlive(false); // 是否保持
        druidDataSource.setResetStatEnable(false);
        druidDataSource.setBreakAfterAcquireFailure(true); // 在请求失败以后是否中断
        druidDataSource.setConnectionErrorRetryAttempts(0); //请求失败尝试次数
    }
}
