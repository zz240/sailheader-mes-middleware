package com.sailheader.middleware.action.consume;

import com.sailheader.middleware.action.ActionHandlerAdapter;
import org.springframework.stereotype.Component;

/**
 * 直接输入不做任何处理
 */
@Component("action.remote.direct")
public class DirectConsume implements ConsumeData{
    @Override
    public void consume(ActionHandlerAdapter<?, ?> actionHandlerAdapter,Object inputData) throws Exception {

    }
}
