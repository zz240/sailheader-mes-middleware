package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.action.remote.db.DruidUtil;
import com.sailheader.middleware.action.remote.db.service.DynamicDataSourceSqlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * 远程访问 db 动作
 */
@Component("action.remote.access.db")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DBRemoteAccess extends RemoteAccess<JSON> {

    @Autowired
    DruidUtil druidUtil;

    @Autowired
    DynamicDataSourceSqlService sourceSqlService;

    @Override
    public JSON access(ActionTempData<JSON> actionTempData) throws Exception {
        String sql = "";
        try {
            // 加载输入数据的sql
            if (actionTempData.getBody() != null) {
                sql = (String) ((JSONObject) actionTempData.getBody()).get("sql");
            }

            // 如果输入数据不存在sql则加载数据源中的sql
            if (sql == null || sql.equals("")) {
                sql = (String) ((JSONObject) dataSource.getAddition()).get("sql");
            }
        } catch (Exception e) {
            throw new Exception("db remote access 输入数据/已选择的数据源数据无法解析!");
        }

        // 为空直接抛出异常
        if (sql == null || sql.equals("")) {
            throw new Exception("db remote access 输入的sql为空");
        }

        return sourceSqlService.executeSql(sql, dataSource);
    }
}
