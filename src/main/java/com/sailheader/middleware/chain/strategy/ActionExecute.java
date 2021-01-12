package com.sailheader.middleware.chain.strategy;

import com.sailheader.middleware.chain.ActionTempData;

/**
 * 动作执行规范
 */
public interface ActionExecute {

    // 处理json对象,使用
    ActionTempData<?> dataHandler(ActionTempData<?> object) throws Exception;

}
