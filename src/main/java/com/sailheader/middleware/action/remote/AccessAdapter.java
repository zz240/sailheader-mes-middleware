package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.model.DataSource;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程访问处理规范
 */
public abstract class AccessAdapter<I> implements Access<JSON, I> {
    @Setter
    @Getter
    protected DataSource dataSource;

    @Override
    public JSON access(ActionTempData<I> actionTempData) throws Exception {
        // 1. 验证数据源
        validateDataSource();
        return execute(actionTempData);
    }

    protected abstract JSON execute(ActionTempData<I> actionTempData) throws Exception;

    protected abstract void validateDataSource() throws Exception;
}
