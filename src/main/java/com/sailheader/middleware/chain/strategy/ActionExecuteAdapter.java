package com.sailheader.middleware.chain.strategy;

import com.sailheader.middleware.chain.ActionTempData;

/**
 * 泛型适配器
 * @param <O>
 * @param <I>
 */

public abstract class ActionExecuteAdapter<O, I> implements ActionExecute{

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> object) throws Exception {

        ActionTempData<I> sendData;
        try {
            sendData = (ActionTempData<I>) object;
        } catch (Exception e) {
            throw new Exception("数据类型不一致!");
        }

        if (sendData == null) {
            throw new Exception("数据类型不一致!");
        }

        return this.execute(sendData);
    }

    public abstract ActionTempData<O> execute(ActionTempData<I> object);
}
