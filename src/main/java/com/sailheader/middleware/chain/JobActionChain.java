package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.action.Action;

/**
 * 动作链
 */

public interface JobActionChain {


    // 添加动作
    JobActionChain addAction(Action action);

    JobActionChain removeAction(Action action);
}
