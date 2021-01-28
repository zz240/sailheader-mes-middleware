package com.sailheader.middleware.chain;

import com.sailheader.middleware.action.ActionHandler;
import lombok.Setter;

import java.util.List;

/**
 * 动作链
 */

public interface JobActionChain<T extends ActionHandler> extends ActionHandler {


    // 添加动作
    JobActionChain<T> addAction(T actionHandler);

    JobActionChain<T> removeAction(T actionHandler);

    void preActionDataBeforeExecute(T actionHandler) throws Exception;

}
