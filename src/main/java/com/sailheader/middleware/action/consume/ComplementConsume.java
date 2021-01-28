package com.sailheader.middleware.action.consume;

import com.sailheader.middleware.action.ActionHandlerAdapter;
import org.springframework.stereotype.Component;


/**
 * 不需要输入数据
 */
@Component("action.consume.completion")
public class ComplementConsume implements ConsumeData{
    @Override
    public void consume(ActionHandlerAdapter<?, ?> actionHandlerAdapter, Object inputData) throws Exception {
        // 消费注入数据
        actionHandlerAdapter.getSendData().setBody(null);
    }
}
