package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.remote.db.service.DynamicDataSourceSqlService;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

/**
 * 远程访问 db 动作
 */
@Component("action.remote.access.db")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DBAccessAdapter extends AccessAdapter<JSON> {

    @Autowired
    DynamicDataSourceSqlService sourceSqlService;

    @Override
    protected JSON execute(ActionTempData<JSON> actionTempData) throws Exception {
        if (actionTempData == null) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "输入数据为空");
        }
        if (actionTempData.getStatus() != ActionStatus.SUCCESS && actionTempData.getStatus() != ActionStatus.INITIAL) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "上一个动作需要成功 或者 动作初始化才能继续执行 db 访问动作!");
        }

        String sql = "";
        try {
            // 加载输入数据的sql
            if (actionTempData.getBody() != null) {
                sql = ((JSONObject) actionTempData.getBody()).getString("sql");
            }

            // 如果输入数据不存在sql则加载数据源中的sql
            if (sql == null || sql.equals("")) {
                sql = dataSource.getAddition().getString("sql");
            }
        } catch (Exception e) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_DB_SQL_IS_NULL, "db remote access 输入数据/已选择的数据源数据无法解析!", e);
        }

        // 为空直接抛出异常
        if (sql == null || sql.equals("")) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_SQL_IS_NULL, "db remote access 输入的sql为空");
        }

        return sourceSqlService.executeSql(sql, dataSource);
    }

    @Override
    protected void validateDataSource() throws Exception {
        if (dataSource == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_DATASOURCE_IS_NULL);
        }

        if (dataSource.getAddition() == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_DATASOURCE_ADDITION_IS_NULL);
        }

        if (dataSource.getAddress() == null || dataSource.getAddress().equals("")) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        JSONObject jsonObject = dataSource.getAddition();
        if (jsonObject.get("driver") == null || jsonObject.get("driver").equals("")) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM, "缺少driver");
        }
        if (jsonObject.get("username") == null || jsonObject.get("username").equals("")) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM, "缺少username");
        }
        if (jsonObject.get("password") == null || jsonObject.get("password").equals("")) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM, "缺少password");
        }

    }
}
