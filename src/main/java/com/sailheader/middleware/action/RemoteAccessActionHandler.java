package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.action.remote.RemoteAccess;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.DataSourceType;
import com.sailheader.middleware.model.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;


/**
 * 远程访问动作
 */
@Component("action.remote.access")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteAccessActionHandler extends ActionHandlerAdapter<JSON, Object> {

    private DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @Override
    protected ActionTempData<JSON> execute(ActionTempData<Object> object) throws Exception {

        // 1. 从map获取bean名称
        DataSourceType dataSourceType = DataSourceType.dataSourceMapById.get(dataSource.getType());
        if (dataSourceType == null || dataSourceType.getBeanName() == null) {
            throw new Exception("未找到注册方法");
        }

        // 2. 获取 spring 中注册的 bean,选择一个远程访问的实现
        RemoteAccess<Object> access;
        try {
            access = context.getBean(dataSourceType.getBeanName(), RemoteAccess.class);
        } catch (Exception e) {
            throw new Exception("未能成功载入" + dataSourceType.getBeanName() + " spring bean");
        }

        // 3. 注入 datasource 给具体的远程访问实现类
        access.setDataSource(dataSource);

        return new ActionTempData<>(ActionStatus.INITIAL, access.access(object));
    }

    @Override
    protected void validateActionData() throws Exception {
        // 提取远程访问中选择的 dataSource
        dataSource = action.getAddition().toJavaObject(DataSource.class);
    }
}


