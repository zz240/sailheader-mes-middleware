package com.sailheader.middleware.service;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;

import java.util.Map;

/**
 * <p>
 * saas数据导入 服务类
 * </p>
 *
 * @author Steven
 * @since 2021-03-05
 */
public interface IDataService {
    OperationResult<?> importData(Map<String, ?> importData) throws Exception;
}
