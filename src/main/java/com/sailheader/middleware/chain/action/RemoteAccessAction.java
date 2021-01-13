package com.sailheader.middleware.chain.action;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.activation.DataSource;

/**
 * 远程访问动作
 */
@Component("action.remoteAccess")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteAccessAction extends ActionAdapter<JSON, JSON>{

    private DataSource dataSource;


    @Override
    protected ActionTempData<JSON> execute(ActionTempData<JSON> object) {
        return null;
    }

    @Override
    protected void validateAddition() throws Exception {
        dataSource = addition.toJavaObject(DataSource.class);
    }
}

/**
 * 远程访问处理规范
 */
interface RemoteAccessHandler {

}
