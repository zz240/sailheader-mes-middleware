package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 远程访问 http 动作
 */
@Component("action.remote.access.http")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HttpAccessAdapter extends AccessAdapter<JSON> {
    @Autowired
    private RestTemplate restTemplate;

    @Override
    protected JSON execute(ActionTempData<JSON> actionTempData) throws Exception {
        return null;
    }

    @Override
    protected void validateDataSource() throws Exception {

    }


}
