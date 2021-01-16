package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.action.remote.db.DruidUtil;
import com.sailheader.middleware.action.remote.db.service.DynamicDataSourceSqlService;
import com.sailheader.middleware.constant.ActionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * 远程访问 db 动作
 */
@Component("action.remote.access.db")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DBAccessAdapter extends AccessAdapter<JSON> {

    @Autowired
    DruidUtil druidUtil;

    @Autowired
    DynamicDataSourceSqlService sourceSqlService;

    @Override
    protected JSON execute(ActionTempData<JSON> actionTempData) throws Exception {
        if (actionTempData.getStatus() != ActionStatus.SUCCESS && actionTempData.getStatus() != ActionStatus.INITIAL) {
            throw new Exception("上一个动作需要成功 或者 动作初始化才能继续执行 db 访问动作!");
        }

        String sql = "";
        try {
            // 加载输入数据的sql
            if (actionTempData.getBody() != null) {
                sql = (String) ((JSONObject) actionTempData.getBody()).get("sql");
            }

            // 如果输入数据不存在sql则加载数据源中的sql
            if (sql == null || sql.equals("")) {
                sql = (String) (dataSource.getAddition()).get("sql");
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

    @Override
    protected void validateDataSource() throws Exception {
        if (dataSource == null) {
            throw new Exception("db数据源不能为空!");
        }

        if (dataSource.getAddition() == null) {
            throw new Exception("db数据源的附加内容不能为空!");
        }

        if (dataSource.getAddress() == null) {
            throw new Exception("db数据源的附加内容不能为空!");
        }

        JSONObject jsonObject = dataSource.getAddition();
        if (jsonObject.get("driver") == null || jsonObject.get("driver").equals("")) {
            throw new Exception("db数据源附加内容的 driver 不能为空!");
        }
        if (jsonObject.get("userName") == null || jsonObject.get("userName").equals("")) {
            throw new Exception("db数据源附加内容的 userName 不能为空!");
        }
        if (jsonObject.get("password") == null || jsonObject.get("password").equals("")) {
            throw new Exception("db数据源附加内容的 password 不能为空!");
        }
    }
}
