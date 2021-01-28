package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.model.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

/**
 * 字段映射动作
 */
public abstract class FieldMappingActionHandler extends ActionHandlerAdapter<JSON, JSON> {
    private DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @Override
    protected ActionTempData execute(ActionTempData object) throws Exception {
        return mapping(object);
    }

    protected abstract ActionTempData mapping(ActionTempData actionTempData);

    @Override
    protected void validateActionAddition() throws Exception {
        if (action.getAddition() == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_IS_NULL);
        }
        try {
            dataSource = JSONObject.parseObject(action.getAddition().toJSONString(),DataSource.class);

        } catch (Exception e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_PARSE_ERROR, e);
        }

        if (dataSource == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_IS_NULL);
        }
        if (dataSource.getName() == null || dataSource.getName().equals("")){
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,"dataSource 中缺少 name 参数");
        }
        if (dataSource.getType() == null || dataSource.getType() == 0){
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,"dataSource 中缺少 type 参数");
        }
        if (dataSource.getAddress() == null || dataSource.getAddress().equals("")){
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,"dataSource 中缺少 address 参数");
        }
        if (dataSource.getAddition() == null){
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,"dataSource 中缺少 addition 参数 ");
        }

    }
}
