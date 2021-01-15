package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.model.DataSource;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程访问处理规范
 */
public abstract class RemoteAccess<I> implements Access<JSON, I> {
    @Setter
    @Getter
    protected DataSource dataSource;

}
