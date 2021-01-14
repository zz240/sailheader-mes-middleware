package com.sailheader.middleware.chain.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.model.DataSource;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("action.remote.http")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HttpRemoteAccess extends RemoteAccess<JSON> {

    @Override
    public JSON access(ActionTempData<JSON> actionTempData) {
        String restUrl = dataSource.getAddress();
        JSONObject jsonObject = (JSONObject) dataSource.getAddition();

        return jsonObject;
    }
}
