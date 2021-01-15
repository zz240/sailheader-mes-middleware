package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
/**
 * 远程访问 http 动作
 */
@Component("action.remote.access.http")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HttpRemoteAccess extends RemoteAccess<JSON> {

    @Override
    public JSON access(ActionTempData<JSON> actionTempData) {
        String restUrl = dataSource.getAddress();
        JSONObject jsonObject = (JSONObject) dataSource.getAddition();

        return jsonObject;
    }
}
