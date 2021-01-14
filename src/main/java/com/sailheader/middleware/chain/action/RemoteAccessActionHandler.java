package com.sailheader.middleware.chain.action;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.chain.action.remote.RemoteAccess;
import com.sailheader.middleware.config.SpringApplicationContext;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.DataSourceType;
import com.sailheader.middleware.model.DataSource;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;


/**
 * 远程访问动作
 */
@Component("action.remoteAccess")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteAccessActionHandler extends ActionHandlerAdapter<JSON, Object> {

    private DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @Override
    protected ActionTempData<JSON> execute(ActionTempData<Object> object) throws Exception {

        // 从map获取bean名称
        DataSourceType dataSourceType = DataSourceType.dataSourceMapById.get(dataSource.getType());
        if (dataSourceType == null || dataSourceType.getBeanName() == null) {
            throw new Exception("未找到注册方法");
        }

        // 获取spring中注册的 bean
        RemoteAccess<Object> access;
        try {
            access = context.getBean(dataSourceType.getBeanName(), RemoteAccess.class);
        } catch (Exception e) {
            throw new Exception("未能成功载入" + dataSourceType.getBeanName() + " spring bean");
        }

        access.setDataSource(dataSource);

        return new ActionTempData<JSON>(ActionStatus.INITIAL, access.access(object));
    }

    @Override
    protected void validateActionData() throws Exception {
        // 提取远程访问中选择的 dataSource
        dataSource = action.getAddition().toJavaObject(DataSource.class);
    }
}


