package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.strategy.ActionExecute;

/**
 * 动作链
 */

public interface JobActionChain {


    // 添加动作
    JobActionChain addAction(ActionExecute actionExecute);

    JobActionChain removeAction(ActionExecute actionExecute);
}
