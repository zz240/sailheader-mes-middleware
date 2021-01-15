package com.sailheader.middleware.chain;

import com.sailheader.middleware.action.ActionHandler;

/**
 * 动作链
 */

public interface JobActionChain extends ActionHandler {


    // 添加动作
    JobActionChain addAction(ActionHandler actionHandler);

    JobActionChain removeAction(ActionHandler actionHandler);

}
