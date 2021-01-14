package com.sailheader.middleware.chain.action.remote;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.model.DataSource;
import org.springframework.context.annotation.Bean;

/**
 * 远程访问处理规范
 */
public abstract class RemoteAccess<I> implements Access<JSON, I> {
    protected DataSource dataSource;

    // 设置远程数据
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

}
