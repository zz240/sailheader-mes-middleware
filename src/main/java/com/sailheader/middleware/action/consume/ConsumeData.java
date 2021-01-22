package com.sailheader.middleware.action.consume;

import com.sailheader.middleware.action.ActionHandlerAdapter;

/**
 * 定义消费输入数据的行为
 */
public interface ConsumeData {
    void consume(ActionHandlerAdapter<?, ?> actionHandlerAdapter, Object inputData) throws Exception;
}
