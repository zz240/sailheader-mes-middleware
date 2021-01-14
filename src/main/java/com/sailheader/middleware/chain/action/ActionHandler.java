package com.sailheader.middleware.chain.action;

import com.sailheader.middleware.chain.ActionTempData;

/**
 * 动作执行规范
 */
public interface ActionHandler {

    // 处理json对象,使用
    ActionTempData<?> dataHandler(ActionTempData<?> object) throws Exception;

}
