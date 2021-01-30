package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.remote.db.service.DynamicDataSourceSqlWithJsonService;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

/**
 * 数据库访问动作
 * 通过解析JSON
 * 构造插入数据库的sql语句
 */
@Component("action.remote.access.db.json")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DBAccessWIthJsonAdapter extends AccessAdapter<JSON> {

    @Autowired
    DynamicDataSourceSqlWithJsonService sourceSqlWithJsonService;

    @Override
    protected JSON execute(ActionTempData<JSON> actionTempData) throws Exception {
        if (actionTempData == null) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "输入数据为空");
        }
        if (actionTempData.getStatus() != ActionStatus.SUCCESS && actionTempData.getStatus() != ActionStatus.INITIAL) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "上一个动作需要成功 或者 动作初始化才能继续执行 db 访问动作!");
        }

        List<Map<String,Object>> inputList = null;
        try {
            // 加载array
            if (actionTempData.getBody() != null) {
                inputList = (List<Map<String, Object>>) actionTempData.getBody();
            }

        } catch (Exception e) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_DB_SQL_IS_NULL, "db remote access with json 输入数据无法解析为list!", e);
        }

        // 为空直接抛出异常
        if (inputList == null || inputList.size() == 0) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_SQL_IS_NULL, "db remote access with json 输入的list为空");
        }

        // 判断执行内容
        int i = sourceSqlWithJsonService.insertAll(dataSource.getAddition().getString("tableName"), inputList, dataSource);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("count", i);
        return jsonObject;
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
        if (jsonObject.get("tableName") == null || jsonObject.get("tableName").equals("")) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM, "缺少tableName");
        }

    }
}
